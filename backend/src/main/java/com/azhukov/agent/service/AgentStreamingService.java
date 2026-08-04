package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStreamingService {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final PromptBuilder promptBuilder;
    private final ContextEngine contextEngine;
    private final ObjectMapper objectMapper;
    private final UsageTracker usageTracker;
    private final AgentProperties properties;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;
    private final IterationBudget iterationBudget;
    private final TurnStateManager turnStateManager;
    private final SessionEntityMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final RuntimeConfigService runtimeConfigService;
    private final ErrorClassifier errorClassifier = new ErrorClassifier();

    private static final int MAX_STREAM_RETRIES = 2;
    private static final int MAX_CONTINUATION_ATTEMPTS = 1;


    public SseEmitter streamTurn(ChatRequest request) {
        return streamTurn(request, new SseEmitter(request.timeoutMs() != null ? request.timeoutMs() : 600_000L));
    }

    /**
     * Apply CLI runtime settings from the request to the session.
     */
    private ChatRequest applyCliState(ChatRequest request) {
        if (request.sessionId() == null) {
            return request;
        }
        SessionEntity session = sessionRepository.findById(request.sessionId()).orElse(null);
        if (session == null) {
            return request;
        }
        // Read current state
        String reasoningEffort = request.reasoningEffort() != null ? request.reasoningEffort() : session.getCliStateValue("reasoningEffort");
        String personality = request.personality() != null ? request.personality() : session.getCliStateValue("personality");
        String queuedPrompt = request.queuedPrompt() != null ? request.queuedPrompt() : session.getCliStateValue("queuedPrompt");
        String subgoal = request.subgoal() != null ? request.subgoal() : session.getSubgoal();
        String goal = request.goal() != null ? request.goal() : session.getCliStateValue("goal");
        if (goal == null || "true".equals(session.getCliStateValue("goalPaused"))) {
            goal = null;
        }
        String subgoals = session.getCliStateValue("subgoals");

        // Build merged request
        String finalMessage = buildMergedMessage(request.message(), queuedPrompt, goal, subgoals, subgoal);
        return new ChatRequest(
            request.sessionId(),
            finalMessage,
            request.delegationDepth(),
            request.timeoutMs(),
            reasoningEffort,
            request.fastMode(),
            request.voiceMode(),
            personality,
            request.enabledTools(),
            request.disabledTools(),
            null, // consumed
            null,
            request.cdpUrl()
        );
    }

    private String buildMergedMessage(String userMessage, String queuedPrompt, String goal, String subgoals, String subgoal) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("[Standing Goal]\n").append(goal).append("\n\n");
        }
        if (subgoals != null && !subgoals.isBlank()) {
            sb.append("[Subgoals]\n").append(subgoals).append("\n\n");
        }
        if (subgoal != null && !subgoal.isBlank()) {
            sb.append("[Goal/Subgoal]\n").append(subgoal).append("\n\n");
        }
        if (queuedPrompt != null && !queuedPrompt.isBlank()) {
            sb.append("[Queued context]\n").append(queuedPrompt).append("\n\n");
        }
        sb.append(userMessage);
        return sb.toString();
    }

    SseEmitter streamTurn(ChatRequest request, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                runAgenticLoop(applyCliState(request), emitter);
            } catch (Exception e) {
                log.error("Streaming failed", e);
                send(emitter, new StreamEvent("error", null, null, e.getMessage()));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void runAgenticLoop(ChatRequest request, SseEmitter emitter) {
        ThinkScrubber scrubber = new ThinkScrubber();

        // Resolve or create session — if sessionId is provided but not found in backend DB,
        // create a new session (the bot may have a sessionId from its own bot_sessions table
        // which is separate from backend's sessions table).
        boolean isNew;
        Session session;
        if (request.sessionId() == null) {
            isNew = true;
            session = createSession("user-1", "openai-compatible", properties.getModel().getModelName());
        } else {
            try {
                isNew = false;
                session = loadSession(request.sessionId());
            } catch (IllegalArgumentException e) {
                log.warn("Session {} not found in backend, creating new session", request.sessionId());
                isNew = true;
                session = createSession("user-1", "openai-compatible", properties.getModel().getModelName());
            }
        }

        // Build messages with full session context (system + history + user)
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));

        // Load existing conversation history for this session
        if (!isNew) {
            List<Message> history = loadHistory(session.id());
            turnMessages.addAll(history);
        }

        // Add user message
        turnMessages.add(Message.user(request.message()));

        // Tools
        List<ToolDefinition> tools = selectTools(request);

        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;
        var budget = iterationBudget.startTurn(session.id());
        turnStateManager.clear(session.id());

        for (int i = 0; i < maxTurns; i++) {
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls",
                    session.id(), budget.modelCalls());
                send(emitter, new StreamEvent("token", "Iteration budget exhausted.", null, null));
                send(emitter, new StreamEvent("done", null, null, null));
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }

            // Prepare context (trimming/summarization as needed)
            List<Message> context = contextEngine.prepareContext(session, turnMessages);

            // Call model — streaming tokens to SSE, with error recovery
            int streamRetries = 0;
            int continuationAttempts = 0;
            ChatResponse response;
            while (true) {
                final StringBuilder contentBuilder = new StringBuilder();
                final List<ToolCall> collectedToolCalls = new ArrayList<>();
                final AtomicReference<Throwable> capturedError = new AtomicReference<>();

                try {
                    modelClient.stream(context, tools, new StreamingResponseHandler() {
                        @Override
                        public void onToken(String token) {
                            String scrubbed = scrubber.scrub(token);
                            if (!scrubbed.isEmpty()) {
                                send(emitter, new StreamEvent("token", scrubbed, null, null));
                                contentBuilder.append(scrubbed);
                            }
                        }

                        @Override
                        public void onToolCalls(List<ToolCall> toolCalls) {
                            collectedToolCalls.addAll(toolCalls);
                            send(emitter, new StreamEvent("tool_calls", null, toolCalls, null));
                        }

                        @Override
                        public void onComplete() {
                            String remaining = scrubber.flush();
                            if (remaining != null && !remaining.isEmpty()) {
                                send(emitter, new StreamEvent("token", remaining, null, null));
                                contentBuilder.append(remaining);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            capturedError.set(error);
                        }
                    });
                } catch (Exception e) {
                    capturedError.set(e);
                }

                budget = iterationBudget.recordModelCall(budget,
                    estimateTokens(context), estimateResponseTokens(contentBuilder.toString(), collectedToolCalls));

                // Handle errors with retry
                if (capturedError.get() != null) {
                    Throwable error = capturedError.get();
                    if (streamRetries < MAX_STREAM_RETRIES) {
                        ErrorClassifier.ErrorType errorType = error instanceof Exception
                            ? errorClassifier.classify((Exception) error)
                            : ErrorClassifier.ErrorType.RETRYABLE;
                        if (errorType == ErrorClassifier.ErrorType.RETRYABLE
                            || errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                            log.warn("Streaming attempt {} failed ({}), retrying: {}",
                                streamRetries + 1, errorType, error.getMessage());
                            send(emitter, new StreamEvent("retry", null, null,
                                "Retrying after " + errorType));
                            streamRetries++;
                            continue;
                        }
                    }
                    // Permanent error or retries exhausted
                    log.error("Model call failed during streaming after {} retries", streamRetries, error);
                    send(emitter, new StreamEvent("error", null, null, "Model call failed: " + error.getMessage()));
                    safeCompleteWithError(emitter, error instanceof Exception
                        ? (Exception) error : new RuntimeException(error));
                    persistTurn(session, turnMessages, isNew);
                    return;
                }

                // Check for truncated response (empty content + no tool calls + no error)
                boolean isEmpty = (contentBuilder.length() == 0) && collectedToolCalls.isEmpty();
                if (isEmpty && continuationAttempts < MAX_CONTINUATION_ATTEMPTS) {
                    log.warn("Truncated response detected (empty content, no tool calls), sending continuation prompt");
                    send(emitter, new StreamEvent("continuation", null, null,
                        "Continuation prompt sent to model"));
                    continuationAttempts++;
                    turnMessages.add(Message.assistant("", turnIndex));
                    turnMessages.add(Message.user("Please continue your response."));
                    context = contextEngine.prepareContext(session, turnMessages);
                    continue;
                }

                // Success — construct response
                if (!collectedToolCalls.isEmpty()) {
                    response = ChatResponse.toolCalls(collectedToolCalls);
                } else {
                    response = ChatResponse.text(contentBuilder.toString());
                }
                break;
            }

            // No tool calls → turn is complete
            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                sendMetadataEvent(emitter, session);
                send(emitter, new StreamEvent("done", null, null, null));
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }

            // Tool calls → execute each, emit tool_result events
            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            TurnState turnState = turnStateManager.getOrStart(session.id(), 1);
            for (ToolCall call : response.toolCalls()) {
                send(emitter, new StreamEvent("tool_start", null, null, null,
                    null, null, null, call.name(), null));

                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(
                    call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;

                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);

                String resultPreview = formatResultPreview(result);
                send(emitter, new StreamEvent("tool_result", null, null, null,
                    null, null, null, call.name(), resultPreview));

                turnMessages.add(Message.toolResult(call.id(), formatResult(result), turnIndex));
            }

            turnIndex++;
        }

        // Max turns reached
        send(emitter, new StreamEvent("token", "Reached maximum turns without completion.", null, null));
        send(emitter, new StreamEvent("done", null, null, null));
        emitter.complete();
        persistTurn(session, turnMessages, isNew);
    }

    private String formatResult(ToolResult result) {
        if (result.success()) {
            return result.content();
        }
        return "Error: " + result.error();
    }

    private String formatResultPreview(ToolResult result) {
        String content = result.success() ? result.content() : "Error: " + result.error();
        int maxLen = 500;
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    private void sendMetadataEvent(SseEmitter emitter, Session session) {
        try {
            String modelUsed = resolveModelUsed(session);
            int contextLength = properties.getContext().getMaxTokens();
            int contextTokens = estimateContextTokens(session.id());
            send(emitter, new StreamEvent("metadata", null, null, null,
                modelUsed, contextTokens, contextLength, null, null));
        } catch (Exception e) {
            log.debug("Failed to send stream metadata event: {}", e.getMessage());
        }
    }

    private List<ToolDefinition> selectTools(ChatRequest request) {
        Set<String> defaultToolsets = new HashSet<>(properties.getSkills().getDefaultToolsets());
        List<ToolDefinition> all = toolRegistry.getDefinitions(defaultToolsets);
        if (request == null) {
            return all;
        }
        if (request.disabledTools() != null && !request.disabledTools().isEmpty()) {
            Set<String> disabled = new HashSet<>(request.disabledTools());
            return all.stream()
                .filter(d -> !disabled.contains(d.name()))
                .toList();
        }
        return all;
    }

    private String resolveModelUsed(Session session) {
        if (session.modelName() != null && !session.modelName().isBlank()) {
            return session.modelName();
        }
        String override = runtimeConfigService.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (properties.getModel() != null
            && properties.getModel().getModelName() != null
            && !properties.getModel().getModelName().isBlank()) {
            return properties.getModel().getModelName();
        }
        return "unknown";
    }

    private int estimateContextTokens(UUID sessionId) {
        if (sessionId == null) return 0;
        UsageDto usage = usageTracker.getSessionUsage(sessionId);
        return usage != null ? usage.tokenEstimate() : 0;
    }

    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message m : messages) {
            chars += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    chars += tc.arguments() != null ? tc.arguments().length() : 0;
                    chars += tc.name() != null ? tc.name().length() : 0;
                }
            }
        }
        return chars / 4 + 1;
    }

    private int estimateResponseTokens(String content, List<ToolCall> toolCalls) {
        int chars = content != null ? content.length() : 0;
        if (toolCalls != null) {
            for (ToolCall tc : toolCalls) {
                chars += tc.arguments() != null ? tc.arguments().length() : 0;
                chars += tc.name() != null ? tc.name().length() : 0;
            }
        }
        return chars / 4 + 1;
    }

    private void persistTurn(Session session, List<Message> turnMessages, boolean isNew) {
        try {
            transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                for (Message m : turnMessages) {
                    // Skip system message — it's regenerated each turn
                    if (m.role() == com.azhukov.agent.core.model.Role.SYSTEM) continue;
                    var e = messageMapper.toEntity(m);
                    e.setSessionId(session.id());
                    e.setCreatedAt(now);
                    messageRepository.save(e);
                }
                // Touch session updated_at
                sessionRepository.findById(session.id()).ifPresent(se -> {
                    se.setUpdatedAt(now);
                    if (isNew && se.getTitle() != null && "New chat".equals(se.getTitle())) {
                        // Maybe set title from first user message
                        se.setTitle(generateTitle(turnMessages));
                    }
                    sessionRepository.save(se);
                });
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to persist streaming turn for session {}: {}", session.id(), e.getMessage());
        }
    }

    private String generateTitle(List<Message> messages) {
        for (Message m : messages) {
            if (m.role() == com.azhukov.agent.core.model.Role.USER) {
                String text = m.content();
                if (text != null && !text.isBlank()) {
                    return text.length() > 50 ? text.substring(0, 50) + "..." : text;
                }
            }
        }
        return "New chat";
    }

    private List<Message> loadHistory(UUID sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
            .stream().map(messageMapper::toDomain).toList();
    }

    private Session createSession(String userId, String provider, String modelName) {
        var e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        var saved = sessionRepository.save(e);
        return sessionMapper.toDomain(saved);
    }

    private Session loadSession(UUID id) {
        var e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        Session session = sessionMapper.toDomain(e);
        if (e.getCliState() != null && !e.getCliState().isEmpty()) {
            for (var entry : e.getCliState().entrySet()) {
                session = session.withMetadata(entry.getKey(), entry.getValue());
            }
        }
        if (e.getSubgoal() != null && !e.getSubgoal().isBlank()) {
            session = session.withMetadata("subgoal", e.getSubgoal());
        }
        return session;
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (IllegalStateException e) {
            // Emitter already completed — ignore
        }
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(event.type())
                .data(objectMapper.writeValueAsString(event)));
        } catch (IllegalStateException e) {
            // Emitter already completed — ignore, don't log
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }
}