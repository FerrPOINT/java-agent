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
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.SessionLineageService;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.StreamContext;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.core.metadata.ModelMetadataService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
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
    private final SessionLineageService sessionLineageService;
    private final CliStateApplier cliStateApplier;
    private final AgentMetrics agentMetrics;
    private final ConversationCompressor conversationCompressor;
    private final ModelMetadataService modelMetadataService;
    private final MidTurnPersistenceCallback midTurnPersistenceCallback;
    private final ErrorClassifier errorClassifier = new ErrorClassifier();

    // ── Shared turn-execution logic (c2: extracted with DefaultAgentRuntime) ──
    // TurnExecutor contains the shared model-call-with-retry, tool execution,
    // think-block stripping, and context-compression-check logic. The streaming
    // path delegates error classification + backoff calculation to it via
    // classifyForRetry(), and uses its static estimateResponseTokens helper.
    // Lazily initialized from existing dependencies to preserve the
    // @RequiredArgsConstructor signature (tests construct this class positionally
    // without going through Spring's @PostConstruct lifecycle).
    // The deps not available here (contextCompressor, approvalQueue,
    // memoryNudgeManager) are passed as null — they are only used by
    // callModelWithRetry/executeToolBatch which the streaming path doesn't call.
    private com.azhukov.agent.core.agent.TurnExecutor turnExecutor;

    private com.azhukov.agent.core.agent.TurnExecutor turnExecutor() {
        if (turnExecutor == null) {
            turnExecutor = new com.azhukov.agent.core.agent.TurnExecutor(
                errorClassifier, properties, null, contextEngine,
                toolExecutionService, toolResultFormatter, tokenEstimator,
                interruptToken, null, null, steerBuffer);
        }
        return turnExecutor;
    }

    private static final int MAX_STREAM_RETRIES = 5;
    private static final int MAX_CONTINUATION_ATTEMPTS = 3;
    // Backoff base/cap are now read from AgentProperties at runtime (see getRetryBackoffBase/Cap)

    // Dedicated executor for streaming tasks — avoids ForkJoinPool.commonPool() starvation
    private final ExecutorService streamingExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("agent-stream-", 0).factory());

    @PreDestroy
    void shutdown() {
        streamingExecutor.shutdown();
        try {
            if (!streamingExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                streamingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    public SseEmitter streamTurn(ChatRequest request) {
        return streamTurn(request, new SseEmitter(request.timeoutMs() != null ? request.timeoutMs() : 600_000L));
    }

    /**
     * Apply CLI runtime settings from the request to the session.
     * Wraps the SessionEntity load and cliState initialization in a
     * read-only transaction to avoid LazyInitializationException when
     * the lazy cliState ElementCollection is accessed from the async
     * streaming thread.
     */
    private ChatRequest applyCliState(ChatRequest request) {
        if (request.sessionId() == null) {
            return request;
        }
        SessionEntity session = transactionTemplate.execute(status -> {
            SessionEntity e = sessionRepository.findById(request.sessionId()).orElse(null);
            if (e != null) {
                // Force-initialize the lazy cliState collection inside the tx
                // so CliStateApplier can safely read values after the tx closes.
                org.hibernate.Hibernate.initialize(e.getCliState());
            }
            return e;
        });
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
            log.warn("Stream error", ex);
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
        }, streamingExecutor);
        return emitter;
    }

    private void runAgenticLoop(ChatRequest request, SseEmitter emitter, StreamContext streamCtx) {
        ThinkScrubber scrubber = new ThinkScrubber();

        // Resolve or create session — if sessionId is provided but not found in backend DB,
        // create a new session (the bot may have a sessionId from its own bot_sessions table
        // which is separate from backend's sessions table).
        var resolved = sessionResolver.resolveOrCreate(
            request.sessionId(),
            request.userId() != null && !request.userId().isBlank() ? request.userId() : "user-1",
            properties.getModel().getModelName());
        boolean isNew = resolved.isNew();
        Session session = resolved.session();

        // Enrich session metadata with user identity from the request so the
        // system prompt volatile tier can include the real name, language, and platform.
        if (request.username() != null && !request.username().isBlank()) {
            session = session.withMetadata("userDisplayName",
                request.firstName() != null && !request.firstName().isBlank()
                    ? request.firstName()
                    : request.username());
        } else if (request.firstName() != null && !request.firstName().isBlank()) {
            session = session.withMetadata("userDisplayName", request.firstName());
        }
        if (request.languageCode() != null && !request.languageCode().isBlank()) {
            session = session.withMetadata("languageCode", request.languageCode());
        }

        // Set the ThreadLocal session ID so LangChain4jModelClient can check cancellation
        InterruptToken.setCurrentSessionId(session.id());

        // M18: Track whether persistTurn has already been called to prevent double-persistence
        java.util.concurrent.atomic.AtomicBoolean persisted = new java.util.concurrent.atomic.AtomicBoolean(false);

        // P1-5: Mid-turn persistence cursor — tracks how many messages have been
        // flushed to the database during this turn. After each tool batch, new
        // messages are persisted immediately, mirroring Hermes' _persist_session.
        int persistedUpTo = 0; // will be set after initial messages are built

        try {

        // Build messages with full session context (system + user)
        // History is loaded by contextEngine.prepareContext() via appendRecentHistory().
        // Do NOT load history here — that would duplicate it in the context.
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder.buildSystemMessage(session));

        // Add user message
        turnMessages.add(Message.user(request.message()));

        // Tools
        List<ToolDefinition> tools = selectTools(request);

        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;
        var budget = iterationBudget.startTurn(session.id());
        turnStateManager.clear(session.id());

        // P1-5: Initialize persistence cursor — all messages so far (system + history + user)
        // are persisted by the end-of-turn persistTurn() call. Mid-turn persistence only
        // covers new messages generated during the agentic loop below.
        persistedUpTo = turnMessages.size();

        for (int i = 0; i < maxTurns; i++) {
            // Check for interrupt at the top of each agentic-loop iteration
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt for session {}", session.id());
                send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls",
                    session.id(), budget.modelCalls());
                String budgetMsg = "⚠️ Iteration budget exhausted (" + budget.modelCalls()
                    + "/" + properties.getBudget().getMaxModelCallsPerTurn() + ")";
                send(emitter, new StreamEvent("token", budgetMsg, null, null), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
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
                        // ── c2: delegate error classification + backoff to TurnExecutor ──
                        // The streaming path can't reuse callModelWithRetry directly (it
                        // streams tokens via a handler), but the error classification and
                        // backoff-delay calculation are shared logic that TurnExecutor owns.
                        ErrorClassifier.ErrorType errorType;
                        long delayMs;
                        if (error instanceof Exception exc) {
                            com.azhukov.agent.core.agent.TurnExecutor.RetryClassification rc =
                                turnExecutor().classifyForRetry(exc, streamRetries);
                            errorType = rc.errorType();
                            delayMs = rc.backoffMs();
                            // Preserve the streaming path's jitter: add 0-500ms on top of
                            // the computed backoff (the synchronous path's jitter is already
                            // included in computeBackoffMs for non-RATE_LIMIT/OVERLOADED types).
                            delayMs += ThreadLocalRandom.current().nextLong(0, 500);
                        } else {
                            errorType = ErrorClassifier.ErrorType.RETRYABLE;
                            long baseMs = properties.getError().getRetryDelayMs();
                            long capMs = properties.getError().getRetryCapMs();
                            delayMs = Math.min(baseMs * (1L << streamRetries), capMs);
                            delayMs += ThreadLocalRandom.current().nextLong(0, 500);
                        }
                        if (errorType == ErrorClassifier.ErrorType.RETRYABLE
                            || errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
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
                                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
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
                                List<Message> compressed = conversationCompressor.compress(context, null);
                                if (compressed.size() < context.size()
                                    || (compressed.size() == context.size()
                                        && compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                                        < context.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum())) {
                                    // Update BOTH context and turnMessages so compression persists
                                    // across iterations (Hermes parity). Without updating turnMessages,
                                    // prepareContext() on the next for-loop iteration would rebuild
                                    // from the uncompressed turnMessages, discarding compression effort.
                                    context = compressed;
                                    turnMessages.clear();
                                    turnMessages.addAll(compressed);
                                    persistedUpTo = turnMessages.size();
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
                    if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                    return;
                }

                // Check for truncated response (empty content + no tool calls + no error)
                boolean isEmpty = (contentBuilder.length() == 0) && collectedToolCalls.isEmpty();
                if (isEmpty && continuationAttempts < MAX_CONTINUATION_ATTEMPTS) {
                    log.warn("Truncated response detected (empty content, no tool calls), sending continuation prompt (attempt {}/{})",
                        continuationAttempts + 1, MAX_CONTINUATION_ATTEMPTS);
                    send(emitter, new StreamEvent("continuation", null, null,
                        "Continuation prompt sent to model"), streamCtx);
                    continuationAttempts++;
                    // Reset state for the retry — previous tool calls/content must not leak
                    contentBuilder.setLength(0);
                    collectedToolCalls.clear();
                    // Build a temporary context with continuation prompt WITHOUT polluting
                    // turnMessages (Hermes marks synthetic messages and strips them before
                    // finalization — we avoid pollution by using a separate list).
                    List<Message> continuationContext = new ArrayList<>(turnMessages);
                    continuationContext.add(Message.assistant("", turnIndex));
                    continuationContext.add(Message.user("Пожалуйста, продолжи свой ответ на языке пользователя."));
                    context = contextEngine.prepareContext(session, continuationContext);
                    continue;
                }

                // Success — construct response
                // Preserve text alongside tool calls — the text is "commentary"
                // (interim assistant message) shown to the user before tool execution.
                // Mirrors Hermes _emit_interim_assistant_message().
                String streamedContent = contentBuilder.toString();
                if (!collectedToolCalls.isEmpty()) {
                    if (streamedContent != null && !streamedContent.isBlank()) {
                        response = ChatResponse.textAndToolCalls(streamedContent, collectedToolCalls);
                    } else {
                        response = ChatResponse.toolCalls(collectedToolCalls);
                    }
                } else {
                    response = ChatResponse.text(streamedContent);
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
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // No tool calls → turn is complete
            if (!response.hasToolCalls()) {
                // Check for empty response after continuation exhaustion — send error to user
                // instead of silently delivering an empty message (Hermes parity)
                if ((response.content() == null || response.content().isBlank())
                        && continuationAttempts >= MAX_CONTINUATION_ATTEMPTS) {
                    log.warn("Empty response after {} continuation attempts for session {} — sending error",
                        continuationAttempts, session.id());
                    String errorMsg = "⚠️ Модель вернула пустой ответ после " + continuationAttempts
                        + " попыток продолжения. Попробуйте переформулировать запрос.";
                    send(emitter, new StreamEvent("token", errorMsg, null, null), streamCtx);
                }
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                sendMetadataEvent(emitter, session, streamCtx, budget.totalInputTokens());
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // Tool calls → execute each, emit tool_result events
            // ── Commentary emission (parity with Hermes _emit_interim_assistant_message) ──
            // When the LLM returns BOTH text AND tool calls, the text is "commentary" —
            // an interim assistant message shown to the user before tool execution.
            // In the streaming path, the text was already shown via onToken callbacks,
            // so we emit a "commentary" event with alreadyStreamed=true to signal the
            // gateway to issue a segment break (visual separator), not a duplicate message.
            if (properties.isCommentaryEnabled() && response.hasContent() && response.hasToolCalls()) {
                send(emitter, new StreamEvent("commentary", response.content(), null, null), streamCtx);
                log.debug("Emitted commentary for session {} (alreadyStreamed=true): {} chars",
                    session.id(), response.content().length());
            }

            turnMessages.add(Message.assistantWithToolCalls(response.content(), response.toolCalls(), turnIndex));

            // P1-5: Persist the assistant message (with tool calls) immediately.
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                }
            }

            // Check interrupt before executing tools
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt before tool execution for session {}", session.id());
                send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
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
                    if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                    return;
                }
                send(emitter, new StreamEvent("tool_start", null,
                    java.util.List.of(new com.azhukov.agent.core.model.ToolCall(call.id(), call.name(), call.arguments())),
                    null, null, null, null, call.name(), null), streamCtx);

                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(
                    call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;

                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);

                String resultPreview = formatResultPreview(result);
                send(emitter, new StreamEvent("tool_result", null, null, null,
                    null, null, null, call.name(), resultPreview), streamCtx);

                String toolResultContent = toolResultFormatter.formatResult(result);
                turnMessages.add(Message.toolResult(call.id(), toolResultContent, turnIndex));
            }

            // M1: Inject pending steer note into the last tool result after all tools complete,
            // matching DefaultAgentRuntime's post-batch steer injection (not per-tool).
            if (steerBuffer != null) {
                String steerText = steerBuffer.consume(session.id());
                if (steerText != null && !turnMessages.isEmpty()) {
                    // Find the last tool result message and append the steer note
                    for (int mi = turnMessages.size() - 1; mi >= 0; mi--) {
                        Message lastMsg = turnMessages.get(mi);
                        if (lastMsg.toolCallId() != null || lastMsg.role() == Role.TOOL) {
                            // M8: Sanitize steer text — strip any steer marker strings to prevent injection
                            String sanitizedSteer = steerText
                                .replace(DefaultPromptBuilder.STEER_MARKER_OPEN, "")
                                .replace(DefaultPromptBuilder.STEER_MARKER_CLOSE, "");
                            String enhancedContent = lastMsg.content() + "\n\n"
                                + DefaultPromptBuilder.STEER_MARKER_OPEN + "\n" + sanitizedSteer + "\n"
                                + DefaultPromptBuilder.STEER_MARKER_CLOSE;
                            turnMessages.set(mi,
                                Message.toolResult(lastMsg.toolCallId(), enhancedContent, lastMsg.turnIndex()));
                            log.info("Injected steer note for session {}", session.id());
                            break;
                        }
                    }
                }
            }

            // P1-5: Persist tool result messages immediately after the batch completes.
            // If the JVM crashes after tool execution but before the next model call,
            // all tool results are preserved in the database.
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                }
            }

            turnIndex++;
        }

        // Max turns reached
        send(emitter, new StreamEvent("token", "Reached maximum turns without completion.", null, null), streamCtx);
        send(emitter, new StreamEvent("done", null, null, null), streamCtx);
        emitter.complete();
        if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
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
        sendMetadataEvent(emitter, session, streamCtx, 0);
    }

    private void sendMetadataEvent(SseEmitter emitter, Session session, StreamContext streamCtx, int lastInputTokens) {
        try {
            String modelUsed = resolveModelUsed(session);
            int contextLength = modelMetadataService.detectContextLength(modelUsed);
            if (contextLength <= 0) {
                contextLength = properties.getContext().getMaxTokens();
            }
            int contextTokens = lastInputTokens > 0
                ? lastInputTokens
                : estimateContextTokens(session.id());
            send(emitter, new StreamEvent("metadata", null, null, null,
                modelUsed, contextTokens, contextLength, null, null, session.id()), streamCtx);
        } catch (Exception e) {
            log.warn("Failed to send stream metadata event: {}", e.getMessage());
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
        // c2: delegate to TurnExecutor's shared static helper
        return com.azhukov.agent.core.agent.TurnExecutor.estimateResponseTokens(content, toolCalls);
    }

    private void persistTurn(Session session, List<Message> turnMessages, boolean isNew) {
        persistTurn(session, turnMessages, isNew, 0);
    }

    private void persistTurn(Session session, List<Message> turnMessages, boolean isNew, int fromIndex) {
        try {
            transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                for (int idx = fromIndex; idx < turnMessages.size(); idx++) {
                    Message m = turnMessages.get(idx);
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
        // Load messages with ancestor context (mirrors Hermes get_messages_as_conversation
        // with include_ancestors=True). After compression rotation, the child session
        // starts fresh — ancestor messages provide historical context.
        return sessionLineageService.loadMessagesWithAncestors(sessionId);
    }

    private void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            // Complete normally — the error has already been sent as an SSE event.
            // Using completeWithError would propagate the exception to Spring's
            // GlobalExceptionHandler, which would try to write a JSON error response
            // on a text/event-stream content type, causing HttpMessageNotWritableException.
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed when trying to complete with error: {}", e.getMessage());
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
            log.debug("SSE event not sent (emitter completed): {}", e.getMessage());
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
            streamCtx.markDisconnected();
        }
    }
}