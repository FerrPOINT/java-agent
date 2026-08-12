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
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.StreamContext;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.metrics.AgentMetrics;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
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
    private final InterruptToken interruptToken;
    private final SteerBuffer steerBuffer;
    private final TokenEstimator tokenEstimator;
    private final ToolResultFormatter toolResultFormatter;
    private final AgentSessionResolver sessionResolver;
    private final CliStateApplier cliStateApplier;
    private final AgentMetrics agentMetrics;
    private final ConversationCompressor conversationCompressor;
    private final ErrorClassifier errorClassifier = new ErrorClassifier();

    private static final int MAX_STREAM_RETRIES = 5;
    private static final int MAX_CONTINUATION_ATTEMPTS = 1;
    private static final long RETRY_BACKOFF_BASE_MS = 2_000L; // 2s base
    private static final long RETRY_BACKOFF_CAP_MS = 60_000L; // cap at 60s


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
        return cliStateApplier.applyCliState(request, session);
    }

    SseEmitter streamTurn(ChatRequest request, SseEmitter emitter) {
        // Create per-stream context (replaces the old singleton volatile boolean)
        StreamContext streamCtx = new StreamContext();

        // Register SseEmitter lifecycle callbacks for cleanup and interrupt
        UUID callbackSessionId = request.sessionId();
        emitter.onTimeout(() -> {
            streamCtx.markDisconnected();
            if (callbackSessionId != null) {
                interruptToken.cancel(callbackSessionId);
            }
            safeCompleteWithError(emitter, new TimeoutException("Stream timed out"));
        });
        emitter.onError(ex -> {
            streamCtx.markDisconnected();
            if (callbackSessionId != null) {
                interruptToken.cancel(callbackSessionId);
            }
            log.warn("Stream error: {}", ex.getMessage());
        });
        emitter.onCompletion(() -> {
            streamCtx.markDisconnected();
            if (callbackSessionId != null) {
                interruptToken.cancel(callbackSessionId);
            }
        });

        CompletableFuture.runAsync(() -> {
            try {
                runAgenticLoop(applyCliState(request), emitter, streamCtx);
            } catch (Exception e) {
                log.error("Streaming failed", e);
                send(emitter, new StreamEvent("error", null, null, e.getMessage()), streamCtx);
                safeCompleteWithError(emitter, e);
            } finally {
                // Safety net: clear ThreadLocal if runAgenticLoop didn't
                InterruptToken.clearCurrentSessionId();
            }
        });
        return emitter;
    }

    private void runAgenticLoop(ChatRequest request, SseEmitter emitter, StreamContext streamCtx) {
        ThinkScrubber scrubber = new ThinkScrubber();

        // Resolve or create session — if sessionId is provided but not found in backend DB,
        // create a new session (the bot may have a sessionId from its own bot_sessions table
        // which is separate from backend's sessions table).
        var resolved = sessionResolver.resolveOrCreate(
            request.sessionId(), "user-1", properties.getModel().getModelName());
        boolean isNew = resolved.isNew();
        Session session = resolved.session();

        // Set the ThreadLocal session ID so LangChain4jModelClient can check cancellation
        InterruptToken.setCurrentSessionId(session.id());

        try {

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
            // Check for interrupt at the top of each agentic-loop iteration
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt for session {}", session.id());
                send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls",
                    session.id(), budget.modelCalls());
                send(emitter, new StreamEvent("token", "Iteration budget exhausted.", null, null), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
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
            final UUID sessionId = session.id();
            while (true) {
                final StringBuilder contentBuilder = new StringBuilder();
                final List<ToolCall> collectedToolCalls = new ArrayList<>();
                final AtomicReference<Throwable> capturedError = new AtomicReference<>();

                try {
                    long llmStart = System.currentTimeMillis();
                    modelClient.stream(context, tools, new StreamingResponseHandler() {
                        @Override
                        public void onToken(String token) {
                            // Check interrupt before emitting each token/chunk
                            if (interruptToken != null && interruptToken.isCancelled(sessionId)) {
                                log.info("Streaming interrupted mid-token for session {}", sessionId);
                                return;
                            }
                            String scrubbed = scrubber.scrub(token);
                            if (!scrubbed.isEmpty()) {
                                send(emitter, new StreamEvent("token", scrubbed, null, null), streamCtx);
                                contentBuilder.append(scrubbed);
                            }
                        }

                        @Override
                        public void onToolCalls(List<ToolCall> toolCalls) {
                            collectedToolCalls.addAll(toolCalls);
                            send(emitter, new StreamEvent("tool_calls", null, toolCalls, null), streamCtx);
                        }

                        @Override
                        public void onComplete() {
                            String remaining = scrubber.flush();
                            if (remaining != null && !remaining.isEmpty()) {
                                send(emitter, new StreamEvent("token", remaining, null, null), streamCtx);
                                contentBuilder.append(remaining);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            capturedError.set(error);
                        }
                    });
                    if (agentMetrics != null) {
                        agentMetrics.llmLatencyTimer().record(System.currentTimeMillis() - llmStart,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    capturedError.set(e);
                }

                budget = iterationBudget.recordModelCall(budget,
                    tokenEstimator.estimateTokens(context), estimateResponseTokens(contentBuilder.toString(), collectedToolCalls));

                // Handle errors with retry
                if (capturedError.get() != null) {
                    Throwable error = capturedError.get();
                    if (streamRetries < MAX_STREAM_RETRIES) {
                        ErrorClassifier.ErrorType errorType = error instanceof Exception
                            ? errorClassifier.classify((Exception) error)
                            : ErrorClassifier.ErrorType.RETRYABLE;
                        if (errorType == ErrorClassifier.ErrorType.RETRYABLE
                            || errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                            // Exponential backoff: 2s, 4s, 8s, 16s, 32s (cap at 60s) + jitter
                            long delayMs = Math.min(
                                RETRY_BACKOFF_BASE_MS * (1L << streamRetries),
                                RETRY_BACKOFF_CAP_MS);
                            delayMs += ThreadLocalRandom.current().nextLong(0, 500);
                            String retryMsg = "⏳ Model overloaded, retrying (attempt "
                                + (streamRetries + 1) + "/" + MAX_STREAM_RETRIES
                                + ") in " + (delayMs / 1000) + "s...";
                            log.warn("Streaming attempt {}/{} failed ({}), retrying in {} ms: {}",
                                streamRetries + 1, MAX_STREAM_RETRIES, errorType, delayMs, error.getMessage());
                            send(emitter, new StreamEvent("retry", null, null, retryMsg), streamCtx);
                            try {
                                Thread.sleep(delayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("Retry backoff interrupted for session {}", session.id());
                                send(emitter, new StreamEvent("error", null, null,
                                    "Retry interrupted: " + ie.getMessage()), streamCtx);
                                safeCompleteWithError(emitter, ie);
                                persistTurn(session, turnMessages, isNew);
                                return;
                            }
                            streamRetries++;
                            continue;
                        }

                        // Context overflow: compress and retry without counting as a retry attempt
                        if (errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW) {
                            log.warn("Context overflow detected during streaming, triggering compression: {}",
                                error.getMessage());
                            send(emitter, new StreamEvent("retry", null, null,
                                "Compressing context..."), streamCtx);
                            try {
                                int targetChars = properties.getContext().getTargetTokens() * 4;
                                List<Message> compressed = conversationCompressor.compress(context, null);
                                if (compressed.size() < context.size()
                                    || (compressed.size() == context.size()
                                        && compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                                        < context.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum())) {
                                    context = compressed;
                                    log.info("Context compressed from {} to {} messages during streaming, retrying",
                                        turnMessages.size(), compressed.size());
                                    // Don't count this as a retry attempt — retry immediately
                                    continue;
                                } else {
                                    log.warn("Context overflow detected, compression did not reduce context, failing: {}",
                                        error.getMessage());
                                }
                            } catch (Exception ce) {
                                log.warn("Context compression failed during streaming: {}", ce.getMessage());
                            }
                            // Compression didn't help — fall through to permanent failure
                        }
                    }
                    // Permanent error or retries exhausted
                    log.error("Model call failed during streaming after {} retries", streamRetries, error);
                    String errorMsg = streamRetries >= MAX_STREAM_RETRIES
                        ? "Model call failed after " + MAX_STREAM_RETRIES + " retries: " + error.getMessage()
                        : "Model call failed: " + error.getMessage();
                    send(emitter, new StreamEvent("error", null, null, errorMsg), streamCtx);
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
                        "Continuation prompt sent to model"), streamCtx);
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

            // Check for interrupt after model response (covers mid-stream cancel)
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt after model response for session {}", session.id());
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }

            // No tool calls → turn is complete
            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                sendMetadataEvent(emitter, session, streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }

            // Tool calls → execute each, emit tool_result events
            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            // Check interrupt before executing tools
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt before tool execution for session {}", session.id());
                send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                persistTurn(session, turnMessages, isNew);
                return;
            }

            TurnState turnState = turnStateManager.getOrStart(session.id(), 1);
            for (ToolCall call : response.toolCalls()) {
                // Check interrupt before each tool execution
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Streaming turn cancelled by interrupt before tool {} for session {}",
                        call.name(), session.id());
                    send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                    send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                    emitter.complete();
                    persistTurn(session, turnMessages, isNew);
                    return;
                }
                send(emitter, new StreamEvent("tool_start", null, null, null,
                    null, null, null, call.name(), null), streamCtx);

                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(
                    call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;

                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);

                String resultPreview = formatResultPreview(result);
                send(emitter, new StreamEvent("tool_result", null, null, null,
                    null, null, null, call.name(), resultPreview), streamCtx);

                String toolResultContent = toolResultFormatter.formatResult(result);
                // Inject pending steer note into the tool result
                if (steerBuffer != null) {
                    String steerText = steerBuffer.consume(session.id());
                    if (steerText != null) {
                        toolResultContent = toolResultContent + "\n\n[STEER NOTE] " + steerText;
                        log.info("Injected steer note for session {}", session.id());
                    }
                }
                turnMessages.add(Message.toolResult(call.id(), toolResultContent, turnIndex));
            }

            turnIndex++;
        }

        // Max turns reached
        send(emitter, new StreamEvent("token", "Reached maximum turns without completion.", null, null), streamCtx);
        send(emitter, new StreamEvent("done", null, null, null), streamCtx);
        emitter.complete();
        persistTurn(session, turnMessages, isNew);
        } finally {
            // Clean up interrupt token map entry and ThreadLocal after stream completion
            interruptToken.remove(session.id());
            InterruptToken.clearCurrentSessionId();
        }
    }

    private String formatResultPreview(ToolResult result) {
        String content = toolResultFormatter.formatResult(result);
        int maxLen = 500;
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    private void sendMetadataEvent(SseEmitter emitter, Session session, StreamContext streamCtx) {
        try {
            String modelUsed = resolveModelUsed(session);
            int contextLength = properties.getContext().getMaxTokens();
            int contextTokens = estimateContextTokens(session.id());
            send(emitter, new StreamEvent("metadata", null, null, null,
                modelUsed, contextTokens, contextLength, null, null), streamCtx);
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
                    // Skip system/developer message — it's regenerated each turn
                    if (m.role() == com.azhukov.agent.core.model.Role.SYSTEM
                            || m.role() == com.azhukov.agent.core.model.Role.DEVELOPER) continue;
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

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (IllegalStateException e) {
            // Emitter already completed — ignore
        }
    }

    private void send(SseEmitter emitter, StreamEvent event, StreamContext streamCtx) {
        if (streamCtx.isClientDisconnected()) return;
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(event.type())
                .data(objectMapper.writeValueAsString(event)));
        } catch (IllegalStateException e) {
            // Emitter already completed — ignore, don't log
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
            streamCtx.markDisconnected();
        }
    }
}