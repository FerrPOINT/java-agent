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
import com.azhukov.agent.persistence.entity.SessionEntity;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
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

    public AgentStreamingService(ModelClient modelClient,
                                  ToolRegistry toolRegistry,
                                  ToolExecutionService toolExecutionService,
                                  PromptBuilder promptBuilder,
                                  ContextEngine contextEngine,
                                  ObjectMapper objectMapper,
                                  UsageTracker usageTracker,
                                  AgentProperties properties,
                                  SessionRepository sessionRepository,
                                  MessageRepository messageRepository,
                                  TransactionTemplate transactionTemplate,
                                  IterationBudget iterationBudget,
                                  TurnStateManager turnStateManager) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.promptBuilder = promptBuilder;
        this.contextEngine = contextEngine;
        this.objectMapper = objectMapper;
        this.usageTracker = usageTracker;
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.transactionTemplate = transactionTemplate;
        this.iterationBudget = iterationBudget;
        this.turnStateManager = turnStateManager;
    }

    public SseEmitter streamTurn(ChatRequest request) {
        return streamTurn(request, new SseEmitter(request.timeoutMs() != null ? request.timeoutMs() : 600_000L));
    }

    SseEmitter streamTurn(ChatRequest request, SseEmitter emitter) {
        CompletableFuture.runAsync(() -> {
            try {
                runAgenticLoop(request, emitter);
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

        // Resolve or create session
        boolean isNew = request.sessionId() == null;
        Session session = isNew
            ? createSession("user-1", "openai-compatible", "")
            : loadSession(request.sessionId());

        // Build messages with full session context (system + history + user)
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));

        // Load existing conversation history for this session
        if (!isNew) {
            List<Message> history = loadHistory(request.sessionId());
            turnMessages.addAll(history);
        }

        // Add user message
        turnMessages.add(Message.user(request.message()));

        // Tools
        List<ToolDefinition> tools = toolRegistry.getDefinitions(
            new HashSet<>(properties.getSkills().getDefaultToolsets()));

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

            // Call model — streaming tokens to SSE
            ChatResponse response;
            try {
                final StringBuilder contentBuilder = new StringBuilder();
                final List<ToolCall> collectedToolCalls = new ArrayList<>();
                final boolean[] hasToolCalls = {false};

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
                        hasToolCalls[0] = true;
                        collectedToolCalls.addAll(toolCalls);
                        // Emit tool_calls event so the client can show tool progress
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
                        send(emitter, new StreamEvent("error", null, null, error.getMessage()));
                        emitter.completeWithError(error);
                    }
                });

                budget = iterationBudget.recordModelCall(budget,
                    estimateTokens(context), estimateResponseTokens(contentBuilder.toString(), collectedToolCalls));

                if (hasToolCalls[0]) {
                    response = ChatResponse.toolCalls(collectedToolCalls);
                } else {
                    response = ChatResponse.text(contentBuilder.toString());
                }
            } catch (Exception e) {
                log.error("Model call failed during streaming", e);
                send(emitter, new StreamEvent("error", null, null, "Model call failed: " + e.getMessage()));
                emitter.completeWithError(e);
                return;
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

    private String resolveModelUsed(Session session) {
        if (session.modelName() != null && !session.modelName().isBlank()) {
            return session.modelName();
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
                    var e = new com.azhukov.agent.persistence.entity.MessageEntity();
                    e.setSessionId(session.id());
                    e.setRole(m.role().name().toLowerCase());
                    e.setContent(m.content());
                    e.setToolCallId(m.toolCallId());
                    if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                        e.setToolCallName(m.toolCalls().get(0).name());
                        e.setToolCallArguments(m.toolCalls().get(0).arguments());
                    }
                    e.setCreatedAt(now);
                    e.setTurnIndex(m.turnIndex() != null ? m.turnIndex() : 0);
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
        List<com.azhukov.agent.persistence.entity.MessageEntity> entities =
            messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Message> history = new ArrayList<>();
        for (var e : entities) {
            history.add(switch (e.getRole()) {
                case "assistant" -> Message.assistant(
                    e.getContent() != null ? e.getContent() : "",
                    e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                case "tool" -> Message.toolResult(
                    e.getToolCallId(),
                    e.getContent() != null ? e.getContent() : "",
                    e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                default -> Message.user(e.getContent() != null ? e.getContent() : "");
            });
        }
        return history;
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
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, java.util.Map.of());
    }

    private Session loadSession(UUID id) {
        var e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        return new Session(e.getId(), e.getUserId(), e.getTitle(),
            e.getModelProvider(), e.getModelName(), null, java.util.Map.of());
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(event.type())
                .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }
}