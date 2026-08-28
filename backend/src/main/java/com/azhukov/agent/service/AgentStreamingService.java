package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;

import com.azhukov.agent.core.agent.TurnExitReason;
import com.azhukov.agent.core.agent.TurnFinalizer;
import com.azhukov.agent.core.agent.ThinkingTimeoutGuidance;
import com.azhukov.agent.core.agent.ResponseRecoveryPolicy;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.context.DefaultContextEngine;
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
import com.azhukov.agent.core.agent.MemoryNudgeManager;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.StreamContext;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolCallValidator;
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


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
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
    private final com.azhukov.agent.core.agent.ToolBatchPipeline toolBatchPipeline;
    // Verify-on-stop guard (Hermes parity: verification_stop.py)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.azhukov.agent.core.agent.VerifyOnStopGuard verifyOnStopGuard;
    // Coding workspace snapshot for verify commands
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.azhukov.agent.core.context.CodingWorkspaceSnapshot codingWorkspaceSnapshot;
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

    /**
     * Operator-configured stream retry cap (Hermes api_max_retries parity).
     * Two-tier (operator decision 2026-08-28): plain errors → 3 attempts,
     * availability errors (RATE_LIMIT/OVERLOADED) → 20 attempts.
     */
    private int maxStreamRetries() {
        return Math.max(1, properties.getError().getRetryAttempts());
    }

    private int maxStreamRetriesFor(ErrorClassifier.ErrorType errorType) {
        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT
            || errorType == ErrorClassifier.ErrorType.OVERLOADED) {
            return Math.max(1, properties.getError().getAvailabilityRetryAttempts());
        }
        return maxStreamRetries();
    }

    /**
     * Hermes parity (gateway _TELEGRAM_NOISY_STATUS_RE): retry chatter is
     * suppressed from chat surfaces except the first attempt or waits >= 300s
     * ("rate limited. waiting \d" / "retrying in \d" are filtered). Backend
     * log always records every attempt.
     */
    static boolean shouldEmitRetryStatus(int attemptIndex, long delayMs) {
        return attemptIndex == 0 || delayMs >= 300_000L;
    }
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

    // ── Background self-improvement wiring (Hermes parity, ported 0.1.16) ──
    // The streaming path previously NEVER initialized or incremented the
    // memory/skill nudge counters (MemoryNudgeManager methods had zero
    // callers here), so background review never fired for streaming turns —
    // i.e. for every Telegram bot turn. Injected via optional setter to keep
    // the @RequiredArgsConstructor signature stable for the 7 positional
    // test constructors.
    private MemoryNudgeManager memoryNudgeManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMemoryNudgeManager(MemoryNudgeManager memoryNudgeManager) {
        this.memoryNudgeManager = memoryNudgeManager;
    }

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
                interruptToken, null, null, null, steerBuffer);
        }
        return turnExecutor;
    }

    // ── SSE event helper (M4 phase B: extracted send/metadata/preview/safeComplete) ──
    private StreamingEventHelper eventHelper;

    private StreamingEventHelper eventHelper() {
        if (eventHelper == null) {
            eventHelper = new StreamingEventHelper(
                objectMapper, toolResultFormatter, modelMetadataService,
                properties, usageTracker, runtimeConfigService);
        }
        return eventHelper;
    }

    // Hermes parity (agent_init.py:2062, #11616): retry count is operator
    // config agent.api_max_retries, default 3. The old hardcoded 5 burned
    // ~10min against a provider cooldown where Hermes gives up in ~3min
    // with an honest error.
    // c2: recovery budgets/nudges live in the SHARED ResponseRecoveryPolicy
    // (single owner for both runtimes — DefaultAgentRuntime + this loop).
    private static final int MAX_LENGTH_CONTINUATION_ATTEMPTS =
        com.azhukov.agent.core.agent.ResponseRecoveryPolicy.MAX_LENGTH_CONTINUATION_ATTEMPTS;
    private static final int MAX_EMPTY_RESPONSE_ATTEMPTS =
        com.azhukov.agent.core.agent.ResponseRecoveryPolicy.MAX_EMPTY_RESPONSE_ATTEMPTS;
    private static final int MAX_DROPPED_TOOLCALL_RETRIES =
        com.azhukov.agent.core.agent.ResponseRecoveryPolicy.MAX_DROPPED_TOOLCALL_RETRIES;
    private static final int MAX_TRUNCATED_TOOL_CALL_RETRIES =
        com.azhukov.agent.core.agent.ResponseRecoveryPolicy.MAX_TRUNCATED_TOOL_CALL_RETRIES;
    /** Hermes jittered_backoff for empty-response retries: base 5s, cap 60s, interruptible. */
    private static final long EMPTY_BACKOFF_BASE_MS = 5_000L;
    private static final long EMPTY_BACKOFF_CAP_MS = 60_000L;
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
        // Hermes parity: the in-process turn has no transport deadline. A fixed
        // 600s SseEmitter cap killed legitimate provider-cooldown retries mid-wait
        // (2026-08-27 21:27:54). 0L disables the container timeout; the client-side
        // idle watchdog (refreshed by keepalive events) governs liveness instead.
        return streamTurn(request, new SseEmitter(request.timeoutMs() != null ? request.timeoutMs() : 0L));
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
            // SSE transport timeout != turn cancellation. A provider cooldown
            // can legally exceed the emitter cap; the turn itself is bounded
            // by its own retry/error budgets. Marking it INTERRUPTED here
            // poisoned history with a fake "interrupted by user" marker
            // (observed 2026-08-27 21:27:54, 600s sharp) and made the next
            // turn re-run the whole plan from scratch.
            log.warn("SSE emitter timed out for session {} — detaching client, turn continues server-side",
                callbackSessionId);
            streamCtx.markDisconnected();
            eventHelper().safeCompleteWithError(emitter, new TimeoutException("Stream timed out"));
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
                long __turnStart = System.currentTimeMillis();
                try {
                    runAgenticLoop(applyCliState(request), emitter, streamCtx);
                } finally {
                    long __turnMs = System.currentTimeMillis() - __turnStart;
                    if (agentMetrics != null) {
                        agentMetrics.recordTurnDuration(__turnMs);
                    }
                    // Perf breakdown: one INFO line per turn with the phases that
                    // dominate latency. Correlate with agent.turn.latency /
                    // agent.compression.rotations / agent.tool.latency in /metrics.
                    if (__turnMs > 5_000) {
                        log.info("Turn performance: {} ms total (session {})", __turnMs,
                            request.sessionId());
                    }
                }
            } catch (Exception e) {
                log.error("Streaming failed", e);
                eventHelper().send(emitter, new StreamEvent("error", null, null, e.getMessage()), streamCtx);
                eventHelper().safeCompleteWithError(emitter, e);
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
        String sessionSource = request.chatType() != null && !request.chatType().isBlank()
            ? "telegram" : "api_server";
        var resolved = sessionResolver.resolveOrCreate(
            request.sessionId(),
            request.userId() != null && !request.userId().isBlank() ? request.userId() : AgentProperties.DEFAULT_USER_ID,
            properties.getModel().getModelName(), sessionSource);
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
        if (request.chatType() != null && !request.chatType().isBlank()) {
            session = session.withMetadata("chatType", request.chatType());
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

        // Reset per-turn loop counters before any model/tool execution.
        toolExecutionService.resetLoopGuardrailForTurn();

        // Tools: select before building the prompt so system guidance matches
        // the exact tool definitions this request exposes (P-04).
        List<ToolDefinition> tools = selectTools(request);

        // Per-request model override must be resolved before context preparation:
        // the context window controls preflight compression. Applying it later
        // made a /model request build context with the previous model's limits.
        String requestModel = request.model() != null && !request.model().isBlank() ? request.model() : null;
        String effectiveModel = requestModel != null ? requestModel : properties.getModel().getModelName();
        contextEngine.updateModel(effectiveModel);

        // Build messages with full session context (system + user)
        // History is loaded by contextEngine.prepareContext() via appendRecentHistory().
        // Do NOT load history here — that would duplicate it in the context.
        List<Message> turnMessages = new ArrayList<>();
        turnMessages.add(promptBuilder instanceof com.azhukov.agent.core.prompt.DefaultPromptBuilder defaultPromptBuilder
            ? defaultPromptBuilder.buildSystemMessageForTools(session, tools.stream()
                .map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()))
            : promptBuilder.buildSystemMessage(session));

        // Add user message
        turnMessages.add(Message.user(request.message()));

        // ── Memory nudge counter (Hermes parity: turn_context.py:704-710) ──
        // Increment at the START of each user turn, hydrated from persisted
        // history on first sight of the session (Hermes issue #22357 / M8).
        // This is what arms the background self-improvement review: without
        // this increment the review thresholds are never reached and the
        // review NEVER fires for streaming (bot) turns.
        if (memoryNudgeManager != null && toolsetsIncludeMemory(request)) {
            try {
                int memNudge = properties.getMemory().getNudgeInterval();
                if (memNudge > 0) {
                    long priorUserTurns = contextEngine.countPriorUserMessages(session.id());
                    memoryNudgeManager.initMemoryCounter(session.id(), priorUserTurns);
                }
                memoryNudgeManager.incrementMemoryTurns(session.id());
            } catch (Exception e) {
                log.debug("Memory nudge counter update failed for {}: {}", session.id(), e.getMessage());
            }
        }

        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;
        var budget = iterationBudget.startTurn(session.id());
        // Hermes parity: wall-clock run-budget wrap-up notice latch (one-shot per turn)
        final long turnStartMillis = System.currentTimeMillis();
        boolean runBudgetWrapupInjected = false;
        turnStateManager.clear(session.id());

        // P1-5: Initialize persistence cursor. The user message is persisted
        // IMMEDIATELY (Hermes parity: _persist_user_message_idx crash persist)
        // — the end-of-turn persistTurn previously started from this cursor
        // and NEVER flushed the user message for tool-less turns, so history
        // in the DB had assistant replies with no user turns (starved
        // countPriorUserMessages hydration and session_search). Mid-turn
        // persistence continues from AFTER the user message.
        if (midTurnPersistenceCallback != null) {
            if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, 0)) {
                persistedUpTo = turnMessages.size();
            }
        } else {
            // No mid-turn callback (unit tests): end-of-turn persistTurn(from=0)
            // flushes everything including the user message.
            persistedUpTo = 1; // after system (skipped on persist anyway)
        }

        // P0: Early metadata event — send the resolved model name BEFORE the first
        // model call so the bot can render the footer model even when the call fails
        // immediately (e.g. billing/usage-limit errors emit no tokens at all).
        // The final metadata event after a successful turn overwrites the token estimate.

        // Per-request model override (/model command or API "model" field):
        // the model was already resolved before context preparation above; record
        // it in session metadata for the footer and metadata event.
        if (requestModel != null) {
            session = session.withMetadata("modelOverride", requestModel);
        }
        eventHelper().sendMetadataEvent(emitter, session, streamCtx);
        com.azhukov.agent.core.client.ModelRequestOptions streamOptions =
            new com.azhukov.agent.core.client.ModelRequestOptions(
                requestModel,
                request.reasoningEffort(),
                request.fastMode(),
                request.voiceMode(),
                request.personality(),
                request.subgoal(),
                null);

        // P-08 (Hermes #92450): bound escaped outer-loop exceptions per turn.
        com.azhukov.agent.core.agent.OuterErrorBudget outerErrors =
            new com.azhukov.agent.core.agent.OuterErrorBudget(maxTurns);
        for (int i = 0; i < maxTurns; i++) {
          try {
            // Check for interrupt at the top of each agentic-loop iteration
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt for session {}", session.id());
                eventHelper().send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls",
                    session.id(), budget.modelCalls());
                String budgetMsg = "⚠️ Iteration budget exhausted (" + budget.modelCalls()
                    + "/" + properties.getBudget().getMaxModelCallsPerTurn() + ")";
                eventHelper().send(emitter, new StreamEvent("token", budgetMsg, null, null), streamCtx);
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // Prepare context (trimming/summarization as needed)
            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            session = resolveRotatedSession(session);

            // Call model — streaming tokens to SSE, with error recovery
            int streamRetries = 0;
            // Hermes parity: separate retry counters (conversation_loop.py) —
            // length_continue_retries (ceiling 4), _empty_content_retries (ceiling 3),
            // and stitched LENGTH fragments (truncated_response_parts).
            int lengthContinueRetries = 0;
            int emptyContentRetries = 0;
            int droppedToolcallRetries = 0;
            int truncatedToolCallRetries = 0;
            // Hermes parity (conversation_loop.py:1862): per-turn compression
            // attempt cap — prevents infinite compress→retry→overflow loops.
            int compressionAttempts = 0;
            final int MAX_COMPRESSION_ATTEMPTS = 3;
            StringBuilder truncatedParts = new StringBuilder();
            boolean lastResponseHadToolCalls = false;
            // R3/R4 (Hermes 7728-7760 + empty_response_guard): per-turn fallback chain
            // and deterministic-empty tracker for the streaming path too.
            com.azhukov.agent.core.agent.FallbackManager streamFallbackManager =
                new com.azhukov.agent.core.agent.FallbackManager(
                    properties.getFallbackChain(),
                    properties.getModel().getProvider(),
                    properties.getModel().getModelName(),
                    properties.getModel().getBaseUrl(),
                    properties.getModel().getApiKey());
            // Compressor is null: this caller is only used for the empty-exhausted
            // fallback activation (no compression paths run through it).
            com.azhukov.agent.core.agent.FallbackModelCaller streamFallbackCaller =
                new com.azhukov.agent.core.agent.FallbackModelCaller(
                    errorClassifier, properties, null, contextEngine);
            com.azhukov.agent.core.agent.EmptyResponseGuard streamEmptyGuard =
                new com.azhukov.agent.core.agent.EmptyResponseGuard();
            ModelClient activeStreamClient = modelClient;
            ChatResponse response;
            final UUID sessionId = session.id();
            while (true) {
                final StringBuilder contentBuilder = new StringBuilder();
                final List<ToolCall> collectedToolCalls = new ArrayList<>();
                final AtomicReference<Throwable> capturedError = new AtomicReference<>();
                final AtomicReference<String> capturedFinishReason = new AtomicReference<>();
                final AtomicReference<Long> capturedOutputTokens = new AtomicReference<>();
                // Hermes parity: think-scrub state is per-RESPONSE (_strip_think_blocks is
                // stateless); reset so hadThinkContent() reflects THIS iteration only.
                scrubber.reset();

                try {
                    long llmStart = System.currentTimeMillis();
                    // Final wire-level repair must run immediately before EVERY
                    // provider call. prepareContext() alone is insufficient:
                    // continuations/compression mutate context afterward.
                    context = HistorySanitizer.sanitizeForModelRequest(context);
                    // Hermes parity: pre-API-call /steer drain (conversation_loop.py:2104-2153).
                    // If a steer arrived during the previous API call, inject it into the
                    // last tool message NOW so the model sees it on THIS iteration. Without
                    // this, steers sent during an API call only land after the NEXT tool batch,
                    // which may never come if the model returns a final response.
                    if (steerBuffer != null) {
                        String preApiSteer = steerBuffer.consume(session.id());
                        if (preApiSteer != null) {
                            String sanitizedSteer = preApiSteer
                                .replace(DefaultPromptBuilder.STEER_MARKER_OPEN, "")
                                .replace(DefaultPromptBuilder.STEER_MARKER_CLOSE, "");
                            String steerMarker = DefaultPromptBuilder.STEER_MARKER_OPEN + "\n"
                                + sanitizedSteer + "\n" + DefaultPromptBuilder.STEER_MARKER_CLOSE;
                            boolean injected = false;
                            for (int si = context.size() - 1; si >= 0; si--) {
                                Message sm = context.get(si);
                                if (sm.toolCallId() != null || sm.role() == Role.TOOL) {
                                    String enhanced = (sm.content() != null ? sm.content() : "") + "\n\n" + steerMarker;
                                    context.set(si, Message.toolResult(sm.toolCallId(), enhanced, sm.turnIndex()));
                                    injected = true;
                                    log.info("Pre-API steer drain: injected into tool msg at index {}", si);
                                    break;
                                }
                            }
                            if (!injected) {
                                // No tool message to inject into — put it back for post-batch drain
                                steerBuffer.steer(session.id(), preApiSteer);
                            }
                        }
                    }
                    // Hermes parity (conversation_loop.py:2154-2172): wall-clock
                    // run-budget wrap-up notice. At 80% of runBudgetSeconds, inject
                    // a one-shot "wrap up and deliver" notice into the newest tool
                    // result. Dormant when runBudgetSeconds is 0 or unset.
                    int runBudget = properties.getBudget().getRunBudgetSeconds();
                    if (runBudget > 0 && !runBudgetWrapupInjected) {
                        long elapsed = (System.currentTimeMillis() - turnStartMillis) / 1000;
                        if (elapsed >= 0.8 * runBudget) {
                            for (int si = context.size() - 1; si >= 0; si--) {
                                Message sm = context.get(si);
                                if (sm.toolCallId() != null || sm.role() == Role.TOOL) {
                                    String enhanced = (sm.content() != null ? sm.content() : "")
                                        + "\n\n" + DefaultPromptBuilder.RUN_BUDGET_WRAPUP_NOTICE;
                                    context.set(si, Message.toolResult(sm.toolCallId(), enhanced, sm.turnIndex()));
                                    runBudgetWrapupInjected = true;
                                    log.info("Run budget wrap-up notice injected (budget={}s, elapsed={}s)",
                                        runBudget, elapsed);
                                    break;
                                }
                            }
                        }
                    }
                    // P-06: OpenRouter empty-response retries bypass the response
                    // cache; the flag is set after each empty attempt and cleared
                    // once the handler completes.
                    com.azhukov.agent.client.langchain4j.EmptyRetryCacheBypass.clear();
                    activeStreamClient.stream(context, tools, streamOptions, new StreamingResponseHandler() {
                        @Override
                        public void onToken(String token) {
                            // Check interrupt before emitting each token/chunk
                            if (interruptToken != null && interruptToken.isCancelled(sessionId)) {
                                log.info("Streaming interrupted mid-token for session {}", sessionId);
                                return;
                            }
                            String scrubbed = scrubber.scrub(token);
                            if (!scrubbed.isEmpty()) {
                                eventHelper().send(emitter, new StreamEvent("token", scrubbed, null, null), streamCtx);
                                contentBuilder.append(scrubbed);
                            }
                        }

                        @Override
                        public void onToolCalls(List<ToolCall> toolCalls) {
                            collectedToolCalls.addAll(toolCalls);
                            eventHelper().send(emitter, new StreamEvent("tool_calls", null, toolCalls, null), streamCtx);
                        }

                        @Override
                        public void onComplete() {
                            String remaining = scrubber.flush();
                            if (remaining != null && !remaining.isEmpty()) {
                                eventHelper().send(emitter, new StreamEvent("token", remaining, null, null), streamCtx);
                                contentBuilder.append(remaining);
                            }
                        }

                        @Override
                        public void onComplete(String finishReason, Long outputTokens) {
                            // Store finish_reason for post-stream routing (LENGTH, CONTENT_FILTER)
                            // and the streamed usage so the deterministic-empty guard (Hermes
                            // empty_response_guard.py) can see zero-output attempts instead of
                            // failing open forever on the streaming path.
                            capturedFinishReason.set(finishReason);
                            capturedOutputTokens.set(outputTokens);
                            onComplete();
                        }

                        @Override
                        public void onError(Throwable error) {
                            capturedError.set(error);
                        }
                    });
                    if (agentMetrics != null) {
log.info("LLM call took {} ms (session {})", System.currentTimeMillis() - llmStart, sessionId);

                        agentMetrics.llmLatencyTimer().record(System.currentTimeMillis() - llmStart,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (Exception e) {
                    capturedError.set(e);
                }

                budget = iterationBudget.recordModelCall(budget,
                    tokenEstimator.estimateTokens(context), estimateResponseTokens(contentBuilder.toString(), collectedToolCalls));

                // Record usage for the streaming path — /usage, /credits and
                // /insights read from usage_log; without this every streamed
                // turn (bot, CLI, e2e) was invisible to usage stats.
                if (capturedError.get() == null) {
                    try {
                        usageTracker.recordTurn(session.id(), session.userId(),
                            properties.getModel().getModelName(),
                            tokenEstimator.estimateTokens(context),
                            estimateResponseTokens(contentBuilder.toString(), collectedToolCalls));
                    } catch (Exception usageEx) {
                        log.debug("Failed to record streaming usage: {}", usageEx.getMessage());
                    }
                }

                // Handle errors with retry
                if (capturedError.get() != null) {
                    Throwable error = capturedError.get();
                    ErrorClassifier.ErrorType preType = ErrorClassifier.ErrorType.RETRYABLE;
                    try {
                        preType = errorClassifier.classify(
                            error instanceof Exception ex2 ? ex2 : new RuntimeException(error));
                    } catch (Exception clsEx) { /* fall back to RETRYABLE */ }
                    int tierCap = maxStreamRetriesFor(preType);
                    var errorClassification = errorClassifier.classifyWithHints(
                        error instanceof Exception ex2 ? ex2 : new RuntimeException(error));
                    // The SSE loop owns its stream client, so it must activate fallbacks
                    // itself. Previously only empty responses could switch models; a hard
                    // billing/auth/model failure went straight to the user even with a
                    // configured fallback chain.
                    if (errorClassification.hints().shouldFallback()
                        && streamFallbackManager.hasPendingFallback()) {
                        com.azhukov.agent.core.agent.FallbackModelCaller.ModelCallContext fallbackContext =
                            new com.azhukov.agent.core.agent.FallbackModelCaller.ModelCallContext(
                                modelClient, streamFallbackManager);
                        fallbackContext.activeClient = activeStreamClient == modelClient ? null : activeStreamClient;
                        if (streamFallbackCaller.tryActivateFallback(
                                fallbackContext, errorClassification.type(),
                                error instanceof Exception ex3 ? ex3 : new RuntimeException(error))) {
                            activeStreamClient = fallbackContext.activeClient;
                            streamRetries = 0;
                            compressionAttempts = 0;
                            eventHelper().send(emitter, new StreamEvent("retry", null, null,
                                "Switching to a fallback model after provider failure."), streamCtx);
                            log.warn("Streaming provider failure ({}) switched to fallback model for session {}",
                                errorClassification.type(), session.id());
                            continue;
                        }
                    }
                    if (streamRetries < tierCap) {
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
                        // An upstream Retry-After >= 60s is not a retryable interactive
                        // failure. LiteLLM emits this for exhausted weekly quota / every
                        // deployment on cooldown. Fail immediately with an actionable user
                        // error instead of occupying the streaming connection for 10 minutes.
                        final long MAX_INTERACTIVE_RETRY_DELAY_MS = 60_000;
                        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT
                            && delayMs >= MAX_INTERACTIVE_RETRY_DELAY_MS) {
                            log.warn("Provider retry-after {} ms is too long for interactive turn; failing fast", delayMs);
                            String quotaHint = error.getMessage() != null
                                && (error.getMessage().toLowerCase().contains("weekly usage")
                                    || error.getMessage().toLowerCase().contains("add extra usage"))
                                ? "Model provider usage limit reached. Add usage or select a model with available capacity."
                                : "All deployments for this model are cooling down. Try another model or wait until capacity returns.";
                            eventHelper().send(emitter, new StreamEvent("error", null, null, quotaHint), streamCtx);
                            eventHelper().safeCompleteWithError(emitter, error instanceof Exception e ? e : new RuntimeException(error));
                            if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew,
                                midTurnPersistenceCallback != null ? persistedUpTo : 0);
                            return;
                        }
                        if (errorType == ErrorClassifier.ErrorType.RETRYABLE
                            || errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                            String retryMsg = "⏳ Model overloaded, retrying (attempt "
                                + (streamRetries + 1) + "/" + tierCap
                                + ") in " + (delayMs / 1000) + "s...";
                            log.warn("Streaming attempt {}/{} failed ({}), retrying in {} ms: {}",
                                streamRetries + 1, tierCap, errorType, delayMs, error.getMessage());
                            // Hermes parity: chat surfaces only see the first attempt or
                            // long (>=300s) waits; the rest stays in logs.
                            if (shouldEmitRetryStatus(streamRetries, delayMs)) {
                                eventHelper().send(emitter, new StreamEvent("retry", null, null, retryMsg), streamCtx);
                            }
                            try {
                                // Interruptible sleep — check cancel flag in small increments
                                // so user cancellation is detected during backoff (Hermes parity).
                                // Hermes _touch_activity fires every 30s during backoff so the
                                // transport never looks dead; a keepalive event does the same
                                // here (any SSE data line refreshes the client idle watchdog).
                                long remaining = delayMs;
                                long sinceKeepalive = 0;
                                while (remaining > 0) {
                                    long chunk = Math.min(remaining, 500);
                                    Thread.sleep(chunk);
                                    remaining -= chunk;
                                    sinceKeepalive += chunk;
                                    if (sinceKeepalive >= 30_000 && remaining > 0) {
                                        eventHelper().send(emitter, new StreamEvent("keepalive", null, null,
                                            "retrying in " + (remaining / 1000) + "s"), streamCtx);
                                        sinceKeepalive = 0;
                                    }
                                    if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                                        log.info("Retry backoff cancelled by interrupt for session {}", session.id());
                                        eventHelper().send(emitter, new StreamEvent("interrupted", null, null,
                                            "Turn cancelled by user."), streamCtx);
                                        eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                                        emitter.complete();
                                        if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                                        return;
                                    }
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.warn("Retry backoff interrupted for session {}", session.id());
                                eventHelper().send(emitter, new StreamEvent("error", null, null,
                                    "Retry interrupted: " + ie.getMessage()), streamCtx);
                                eventHelper().safeCompleteWithError(emitter, ie);
                                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                                return;
                            }
                            streamRetries++;
                            continue;
                        }

                        // Context overflow: compress and retry without counting as a retry attempt
                        if (errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW
                            && compressionAttempts < MAX_COMPRESSION_ATTEMPTS) {
                            compressionAttempts++;
                            log.warn("Context overflow detected during streaming, triggering compression attempt {}/{}: {}",
                                compressionAttempts, MAX_COMPRESSION_ATTEMPTS, error.getMessage());
                            eventHelper().send(emitter, new StreamEvent("retry", null, null,
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
                                    context = HistorySanitizer.sanitize(compressed);
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
                    String errorMsg = streamRetries >= tierCap
                        ? "Model call failed after " + tierCap + " retries: " + error.getMessage()
                        : "Model call failed: " + error.getMessage();
                    // Hermes parity (thinking_timeout_guidance.py): detect reasoning
                    // model thinking-phase transport kill and append specific guidance.
                    String modelName = streamOptions != null ? streamOptions.modelName() : null;
                    if (modelName == null) modelName = properties.getModel().getModelName();
                    ErrorClassifier.ErrorType finalErrorType;
                    try {
                        finalErrorType = errorClassifier.classify(
                            error instanceof Exception e ? e : new RuntimeException(error));
                    } catch (Exception ce) {
                        finalErrorType = ErrorClassifier.ErrorType.RETRYABLE;
                    }
                    if (ThinkingTimeoutGuidance.isThinkingTimeout(finalErrorType, modelName, error.getMessage())) {
                        log.info("Thinking-phase timeout detected for reasoning model {} — appending guidance", modelName);
                        String provider = !properties.getFallbackChain().isEmpty()
                            ? properties.getFallbackChain().get(0).getProvider()
                            : "your-provider";
                        errorMsg += ThinkingTimeoutGuidance.buildGuidance(provider, modelName);
                    }
                    // Hermes parity (_CONTENT_POLICY_RECOVERY_HINT): append recovery hint
                    // to content-policy refusal messages so the user gets actionable advice.
                    if (finalErrorType == ErrorClassifier.ErrorType.CONTENT_POLICY
                        && !errorMsg.contains(ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT)) {
                        errorMsg += "\n\n" + ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT;
                    }
                    eventHelper().send(emitter, new StreamEvent("error", null, null, errorMsg), streamCtx);
                    eventHelper().safeCompleteWithError(emitter, error instanceof Exception
                        ? (Exception) error : new RuntimeException(error));
                    if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                    return;
                }

                // ── finish_reason routing (Hermes parity: conversation_loop.py:3354-3506) ──
                String finishReason = capturedFinishReason.get();
                boolean hasContent = contentBuilder.length() > 0;
                boolean hasToolCalls = !collectedToolCalls.isEmpty();
                if (finishReason != null) {
                    log.info("finish_reason={} for session {} (content={} chars, toolCalls={})",
                        finishReason, session.id(), contentBuilder.length(), collectedToolCalls.size());
                }

                // CONTENT_FILTER: model declined due to content policy
                if ("CONTENT_FILTER".equals(finishReason) && !hasToolCalls) {
                    log.warn("Content filter triggered for session {} — model declined response", session.id());
                    String filterMsg = (contentBuilder.length() > 0 ? contentBuilder.toString().strip() + "\n\n" : "")
                        + ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT;
                    eventHelper().send(emitter, new StreamEvent("token", filterMsg, null, null), streamCtx);
                    response = ChatResponse.text(filterMsg);
                    break;
                }

                // ── Truncated tool call recovery (Hermes parity: conversation_loop.py:3829-3860) ──
                // LENGTH finish_reason WITH tool calls: the model hit the output cap
                // mid-tool-call JSON. The arguments are truncated/incomplete and must
                // NOT be executed. Re-run the same API call with a boosted max_tokens
                // (2^attempt × base, capped at 32768) giving the model room to finish.
                if (com.azhukov.agent.core.agent.ResponseRecoveryPolicy.isTruncatedToolCall(finishReason, hasToolCalls)
                        && truncatedToolCallRetries < MAX_TRUNCATED_TOOL_CALL_RETRIES) {
                    truncatedToolCallRetries++;
                    int boostedMax = com.azhukov.agent.core.agent.ResponseRecoveryPolicy.boostedMaxTokens(
                        properties.getModel().getMaxTokens(), truncatedToolCallRetries);
                    log.warn("Truncated tool call detected (LENGTH + {} tool call(s), session {}) — retrying with boosted max_tokens={} (attempt {}/{})",
                        collectedToolCalls.size(), session.id(), boostedMax, truncatedToolCallRetries, MAX_TRUNCATED_TOOL_CALL_RETRIES);
                    eventHelper().send(emitter, new StreamEvent("retry", null, null,
                        "Truncated tool call — retrying with larger output budget ("
                            + truncatedToolCallRetries + "/" + MAX_TRUNCATED_TOOL_CALL_RETRIES + ")"), streamCtx);
                    // Don't append the broken response; re-run from current context
                    // with a boosted max_tokens so the model has room to complete the JSON.
                    contentBuilder.setLength(0);
                    collectedToolCalls.clear();
                    scrubber.reset();
                    com.azhukov.agent.core.client.ModelRequestOptions boostedOptions =
                        new com.azhukov.agent.core.client.ModelRequestOptions(
                        streamOptions.modelName(), streamOptions.reasoningEffort(),
                        streamOptions.fastMode(), streamOptions.voiceMode(),
                        streamOptions.personality(), streamOptions.subgoal(),
                        boostedMax);
                    context = contextEngine.prepareContext(session, turnMessages);
                    session = resolveRotatedSession(session);
                    // Re-issue the stream call with boosted max_tokens
                    activeStreamClient.stream(context, tools, boostedOptions, new StreamingResponseHandler() {
                        @Override
                        public void onToken(String token) {
                            String scrubbed = scrubber.scrub(token);
                            if (!scrubbed.isEmpty()) {
                                contentBuilder.append(scrubbed);
                            }
                        }

                        @Override
                        public void onToolCalls(List<ToolCall> toolCalls) {
                            collectedToolCalls.addAll(toolCalls);
                        }

                        @Override
                        public void onComplete() {
                            // No-op — finish_reason + outputTokens come via the 2-arg overload
                        }

                        @Override
                        public void onComplete(String finishReason2, Long outputTokens2) {
                            capturedFinishReason.set(finishReason2);
                            capturedOutputTokens.set(outputTokens2);
                        }

                        @Override
                        public void onError(Throwable error) {
                            capturedError.set(error);
                        }
                    });
                    // Re-stream completed (blocking call) — check for error first,
                    // then re-read routing flags and fall through to finish_reason
                    // routing in THIS iteration. Do NOT 'continue' back to while(true)
                    // — that would recreate contentBuilder/collectedToolCalls/captured*
                    // and silently discard the re-streamed response.
                    if (capturedError.get() != null) {
                        Throwable retryError = capturedError.get();
                        log.error("Re-stream with boosted max_tokens failed for session {}: {}",
                            session.id(), retryError.getMessage());
                        eventHelper().send(emitter, new StreamEvent("error", null, null,
                            "Model call failed during truncated tool call retry: " + retryError.getMessage()), streamCtx);
                        eventHelper().safeCompleteWithError(emitter, retryError instanceof Exception
                            ? (Exception) retryError : new RuntimeException(retryError));
                        if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew,
                            midTurnPersistenceCallback != null ? persistedUpTo : 0);
                        return;
                    }
                    // Re-read routing flags from the re-streamed response
                    finishReason = capturedFinishReason.get();
                    hasContent = contentBuilder.length() > 0;
                    hasToolCalls = !collectedToolCalls.isEmpty();
                    log.info("Re-stream finish_reason={} for session {} (content={} chars, toolCalls={})",
                        finishReason, session.id(), contentBuilder.length(), collectedToolCalls.size());
                    // Fall through to ceiling check + normal finish_reason routing below
                }

                // Truncated tool call ceiling reached — refuse to execute incomplete arguments.
                if (com.azhukov.agent.core.agent.ResponseRecoveryPolicy.isTruncatedToolCall(finishReason, hasToolCalls)
                        && truncatedToolCallRetries >= MAX_TRUNCATED_TOOL_CALL_RETRIES) {
                    log.warn("Truncated tool call after {} retries — refusing to execute incomplete tool arguments (session {})",
                        truncatedToolCallRetries, session.id());
                    eventHelper().send(emitter, new StreamEvent("token", "\n\n⚠️ Tool call remained truncated after "
                        + truncatedToolCallRetries + " retries — the action was not executed.", null, null), streamCtx);
                    // Close the interrupted tool sequence with a recovery stub
                    for (ToolCall tc : collectedToolCalls) {
                        turnMessages.add(Message.assistantWithToolCalls(contentBuilder.toString(),
                            List.of(tc), turnIndex));
                        turnMessages.add(Message.toolResult(tc.pairingId(),
                            "[Truncated tool call — arguments were incomplete after "
                            + truncatedToolCallRetries + " retries. The tool was not executed.]",
                            turnIndex));
                    }
                    contentBuilder.setLength(0);
                    collectedToolCalls.clear();
                    // Continue the loop — the model sees the stub and can retry properly
                    turnIndex++;
                    context = contextEngine.prepareContext(session, turnMessages);
                    session = resolveRotatedSession(session);
                    continue;
                }

                // LENGTH: model hit max output tokens — partial content.
                // Hermes parity (conversation_loop.py:3711-3775): the partial fragment is
                // ACCUMULATED into truncatedParts and stitched into the final response;
                // ceiling is 4 attempts; on exhaustion the stitched partial is KEPT.
                // Also handle finish_reason="incomplete" with incomplete_details.reason
                // = "max_output_tokens" — Hermes treats this as a synonym for LENGTH
                // (conversation_loop.py:3555-3563).
                boolean isLengthTruncation = "LENGTH".equals(finishReason)
                    || "incomplete".equalsIgnoreCase(finishReason);
                if (isLengthTruncation && hasContent && !hasToolCalls
                        && lengthContinueRetries < MAX_LENGTH_CONTINUATION_ATTEMPTS) {
                    log.info("LENGTH truncation detected for session {} — partial content ({} chars), sending continuation (attempt {}/{})",
                        session.id(), contentBuilder.length(), lengthContinueRetries + 1, MAX_LENGTH_CONTINUATION_ATTEMPTS);
                    eventHelper().send(emitter, new StreamEvent("continuation", null, null,
                        "Continuation prompt sent to model (LENGTH)"), streamCtx);
                    lengthContinueRetries++;
                    turnIndex++;
                    // Stitch: keep the partial fragment, ask the model to continue from it.
                    String partialContent = contentBuilder.toString();
                    truncatedParts.append(partialContent);
                    contentBuilder.setLength(0);
                    collectedToolCalls.clear();
                    List<Message> lengthContext = new ArrayList<>(turnMessages);
                    lengthContext.add(Message.assistant(partialContent, turnIndex));
                    lengthContext.add(Message.user(com.azhukov.agent.core.agent.ResponseRecoveryPolicy.LENGTH_NUDGE));
                    context = contextEngine.prepareContext(session, lengthContext);
                    session = resolveRotatedSession(session);
                    continue;
                }

                // LENGTH ceiling reached with partial content — Hermes keeps the stitched
                // partial instead of discarding it (conversation_loop.py:3779-3813).
                if (isLengthTruncation && hasContent && !hasToolCalls
                        && lengthContinueRetries >= MAX_LENGTH_CONTINUATION_ATTEMPTS) {
                    String stitched = truncatedParts.toString() + contentBuilder;
                    log.warn("Response still truncated after {} continuation attempts for session {} — keeping partial ({} chars)",
                        lengthContinueRetries, session.id(), stitched.length());
                    eventHelper().send(emitter, new StreamEvent("token", "\n\n⚠️ Ответ остался обрезанным после "
                        + lengthContinueRetries + " попыток продолжения — сохранена полученная часть.", null, null), streamCtx);
                    response = ChatResponse.text(stitched);
                    break;
                }

                // ── Dropped tool-call recovery (Hermes parity: conversation_loop.py:7918-7950) ──
                // Some providers return finish_reason="tool_calls" while the parsed array is
                // empty — the model signalled it wanted to act but shipped no call. Reaching
                // finalization with that mismatch would end the turn with the task unstarted.
                // Re-prompt (bounded to 3 CONSECUTIVE stalls; budget resets after any
                // successful tool round). finish_reason="stop" text finishes never enter this.
                if ("TOOL_EXECUTION".equals(finishReason) && collectedToolCalls.isEmpty()
                        && droppedToolcallRetries < MAX_DROPPED_TOOLCALL_RETRIES) {
                    droppedToolcallRetries++;
                    log.warn("finish_reason=tool_calls with empty tool_calls array (narration only) — re-prompting to emit the call (retry {}/{}, session {})",
                        droppedToolcallRetries, MAX_DROPPED_TOOLCALL_RETRIES, session.id());
                    eventHelper().send(emitter, new StreamEvent("retry", null, null,
                        "Model signaled a tool call but sent none — re-prompting ("
                            + droppedToolcallRetries + "/" + MAX_DROPPED_TOOLCALL_RETRIES + ")"), streamCtx);
                    turnIndex++;
                    // Ephemeral recovery pair (Hermes flags both _dropped_toolcall_nudge so the
                    // persistence layer skips them): build a separate context, don't pollute turnMessages.
                    List<Message> droppedContext = new ArrayList<>(turnMessages);
                    if (contentBuilder.length() > 0) {
                        droppedContext.add(Message.assistant(contentBuilder.toString(), turnIndex));
                    }
                    droppedContext.add(Message.user(com.azhukov.agent.core.agent.ResponseRecoveryPolicy.DROPPED_TOOLCALL_NUDGE));
                    contentBuilder.setLength(0);
                    context = contextEngine.prepareContext(session, droppedContext);
                    session = resolveRotatedSession(session);
                    continue;
                }

                // Check for truncated response (empty content + no tool calls + no error)
                // ThinkScrubber strips reasoning blocks — if the response was think-only,
                // contentBuilder will be empty but hadThinkContent() is true. In that case
                // the response is NOT truncated — the model just produced reasoning only.
                boolean isEmpty = (contentBuilder.length() == 0) && collectedToolCalls.isEmpty()
                    && !scrubber.hadThinkContent();
                streamEmptyGuard.recordEmptyAttempt(
                    properties.getModel().getModelName(),
                    properties.getModel().getProvider(),
                    capturedFinishReason.get(), capturedOutputTokens.get());
                boolean streamDeterministicEmpty = streamEmptyGuard.deterministicEmpty();
                if (streamDeterministicEmpty) {
                    log.warn("Deterministic empty response in stream (consecutive zero-output) — skipping remaining retries");
                    eventHelper().send(emitter, new StreamEvent("continuation", null, null,
                        "⚠️ Модель детерминированно возвращает пустой ответ — ретраи остановлены"), streamCtx);
                }
                if (isEmpty && !streamDeterministicEmpty && emptyContentRetries >= MAX_EMPTY_RESPONSE_ATTEMPTS
                        && streamFallbackManager.hasPendingFallback()) {
                    // R3 (Hermes 7728-7760): empty budget burned — try the next fallback
                    // provider BEFORE the terminal "(empty)".
                    com.azhukov.agent.core.agent.FallbackModelCaller.ModelCallContext fmc =
                        new com.azhukov.agent.core.agent.FallbackModelCaller.ModelCallContext(
                            modelClient, streamFallbackManager);
                    fmc.activeClient = activeStreamClient == modelClient ? null : activeStreamClient;
                    if (streamFallbackCaller.tryActivateFallbackForEmpty(fmc)) {
                        activeStreamClient = fmc.activeClient;
                        emptyContentRetries = 0;
                        streamEmptyGuard.reset();
                        eventHelper().send(emitter, new StreamEvent("continuation", null, null,
                            "↻ Переключение на fallback-провайдера после пустых ответов"), streamCtx);
                        log.warn("Empty responses exhausted in stream — switched to fallback model");
                        continue;
                    }
                }
                if (isEmpty && !streamDeterministicEmpty && emptyContentRetries < MAX_EMPTY_RESPONSE_ATTEMPTS) {
                    emptyContentRetries++;
                    log.warn("Empty response (no content or reasoning) — retry {}/{} with backoff (model returned empty)",
                        emptyContentRetries, MAX_EMPTY_RESPONSE_ATTEMPTS);
                    // P-06: the NEXT stream call must bypass OpenRouter response cache
                    com.azhukov.agent.client.langchain4j.EmptyRetryCacheBypass.markEmptyRetry();
                    eventHelper().send(emitter, new StreamEvent("continuation", null, null,
                        "Empty response from model — retrying (" + emptyContentRetries + "/" + MAX_EMPTY_RESPONSE_ATTEMPTS + ")"), streamCtx);
                    // Hermes parity (conversation_loop.py:7657): jittered backoff base 5s,
                    // cap 60s, interruptible in small increments. Base/cap configurable.
                    long backoffMs = jitteredBackoffMs(emptyContentRetries,
                        properties.getCore().getEmptyBackoffBaseMs(),
                        properties.getCore().getEmptyBackoffCapMs());
                    if (!interruptibleSleep(backoffMs, session.id(), interruptToken)) {
                        log.info("Empty-response backoff interrupted for session {}", session.id());
                    }
                    // Reset state for the retry — previous tool calls/content must not leak
                    contentBuilder.setLength(0);
                    collectedToolCalls.clear();
                    scrubber.reset();
                    turnIndex++; // Increment turnIndex for each continuation attempt (Hermes parity)
                    // Build a temporary context with continuation prompt WITHOUT polluting
                    // turnMessages (Hermes marks synthetic messages and strips them before
                    // finalization — we avoid pollution by using a separate list).
                    List<Message> continuationContext = new ArrayList<>(turnMessages);
                    // Post-tool empty nudge: if the previous response had tool calls,
                    // use a specific nudge telling the model to process tool results
                    // (mirrors Hermes _EMPTY_TOOL_RESPONSE_NUDGE). A tool-call round means
                    // role alternation must NOT get an empty assistant stub — strict providers
                    // (Moonshot/Kimi) reject {"role":"assistant","content":""} with HTTP 400
                    // (Hermes _is_empty_partial_stub, conversation_loop.py:3718-3730).
                    String nudgeText = lastResponseHadToolCalls
                        ? com.azhukov.agent.core.agent.ResponseRecoveryPolicy.EMPTY_AFTER_TOOLS_NUDGE
                        : com.azhukov.agent.core.agent.ResponseRecoveryPolicy.EMPTY_NUDGE;
                    // ── Empty-response nudge semantics (Hermes conversation_loop.py:7568-7577) ──
                    // Post-tool round: assistant("(empty)") synthetic stub + nudge user message —
                    // a tool→user sequence without the stub is rejected by strict providers
                    // (Moonshot/Kimi) and would poison the next replay (HTTP 400).
                    // Plain-empty round: NO synthetic messages at all — backoff + fresh call
                    // (Hermes adds nothing to the context; the retry re-sends as-is).
                    if (lastResponseHadToolCalls) {
                        continuationContext.add(Message.assistant("(empty)", turnIndex));
                        continuationContext.add(Message.user(nudgeText));
                    }
                    // Plain-empty: nudgeText intentionally NOT added — fresh call only.
                    context = contextEngine.prepareContext(session, continuationContext);
                    session = resolveRotatedSession(session);
                    continue;
                }

                // Success — construct response
                // Preserve text alongside tool calls — the text is "commentary"
                // (interim assistant message) shown to the user before tool execution.
                // Mirrors Hermes _emit_interim_assistant_message().
                String streamedContent = contentBuilder.toString();
                if (!collectedToolCalls.isEmpty()) {
                    lastResponseHadToolCalls = true;
                    // Hermes parity (conversation_loop.py:7133): a landed tool call resets
                    // the dropped-toolcall stall budget.
                    droppedToolcallRetries = 0;
                    if (streamedContent != null && !streamedContent.isBlank()) {
                        response = ChatResponse.textAndToolCalls(streamedContent, collectedToolCalls);
                    } else {
                        response = ChatResponse.toolCalls(collectedToolCalls);
                    }
                } else {
                    lastResponseHadToolCalls = false;
                    // Hermes parity (conversation_loop.py:7888-7893): a successful response
                    // after LENGTH continuations joins all stitched fragments.
                    if (truncatedParts.length() > 0) {
                        response = ChatResponse.text(truncatedParts + streamedContent);
                    } else {
                        response = ChatResponse.text(streamedContent);
                    }
                }
                break;
            }

            // Check for interrupt after model response (covers mid-stream cancel)
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Streaming turn cancelled by interrupt after model response for session {}", session.id());
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                eventHelper().send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // No tool calls → turn is complete
            if (!response.hasToolCalls()) {
                // ── Verify-on-stop guard (Hermes parity: verification_stop.py) ──
                // When the model finishes (STOP) after editing code without fresh
                // verification evidence, inject a nudge requesting tests/build.
                if (properties.getVerifyOnStop().isEnabled()
                    && toolExecutionService.getFileMutationTracker() != null) {
                    var tracker = toolExecutionService.getFileMutationTracker();
                    var changedPaths = tracker.getTurnMutationPaths();
                    if (!changedPaths.isEmpty()
                        && tracker.getVerificationStopNudges() < verifyOnStopGuard.getMaxNudgeAttempts()) {
                        java.util.List<String> verifyCommands = codingWorkspaceSnapshot != null
                            ? codingWorkspaceSnapshot.getVerifyCommands() : java.util.List.of();
                        String nudge = verifyOnStopGuard.buildNudge(
                            changedPaths, tracker.getVerificationStopNudges(), verifyCommands);
                        if (nudge != null) {
                            tracker.incrementVerificationStopNudges();
                            log.info("Verify-on-stop nudge (streaming) for session {} (attempt {}, {} changed paths)",
                                session.id(), tracker.getVerificationStopNudges(), changedPaths.size());
                            // Emit the assistant response as interim, then inject nudge
                            turnMessages.add(Message.assistant(response.content(), turnIndex));
                            turnMessages.add(Message.user(nudge));
                            // Persist interim before continuing
                            if (midTurnPersistenceCallback != null) {
                                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                                    persistedUpTo = turnMessages.size();
                                }
                            }
                            // Continue the agentic loop — model gets another turn to verify
                            continue;
                        }
                    }
                }

                // ── Background self-improvement review (Hermes parity:
                // turn_finalizer.py:790-802) ──
                // Fire AFTER the final response is delivered, not before:
                // the review must not delay the user-visible answer. It runs
                // async (scheduled with delay) and cannot fail the turn.
                if (memoryNudgeManager != null) {
                    try {
                        boolean interrupted = interruptToken != null && interruptToken.isCancelled(session.id());
                        memoryNudgeManager.triggerNudgedBackgroundReview(session, turnMessages, interrupted);
                    } catch (Exception e) {
                        log.debug("Background review trigger failed for {}: {}", session.id(), e.getMessage());
                    }
                }
                // Check for empty response after continuation exhaustion — send error to user
                // instead of silently delivering an empty message (Hermes parity)
                if ((response.content() == null || response.content().isBlank())
                        && emptyContentRetries >= MAX_EMPTY_RESPONSE_ATTEMPTS) {
                    log.warn("Empty response after {} empty-response retries for session {} — sending error",
                        emptyContentRetries, session.id());
                    String errorMsg = "⚠️ Модель вернула пустой ответ после " + emptyContentRetries
                        + " попыток продолжения. Попробуйте переформулировать запрос.";
                    eventHelper().send(emitter, new StreamEvent("token", errorMsg, null, null), streamCtx);
                }
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                // Self-improvement (Hermes parity): surface a PENDING review
                // summary from an earlier turn's background review as an SSE
                // "review" event before "done" — the bot renders it as
                // "💾 Self-improvement review: …". This turn's review runs
                // async and surfaces on the NEXT turn (Hermes pending-release
                // semantics via background_review_callback).
                try {
                    String pendingReview = memoryNudgeManager != null
                        ? memoryNudgeManager.getReviewSummaryForSurface(session.id()) : null;
                    if (pendingReview != null && !pendingReview.isBlank()) {
                        eventHelper().send(emitter, new StreamEvent("review", null, null, pendingReview), streamCtx);
                    }
                } catch (Exception reviewEx) {
                    log.debug("Review summary surface failed for {}: {}", session.id(), reviewEx.getMessage());
                }
                eventHelper().sendMetadataEvent(emitter, session, streamCtx, budget.totalInputTokens());
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // Tool calls → execute each, emit tool_result events
            // ── Uniquify duplicate tool-call ids BEFORE any downstream consumer ──
            // (Hermes conversation_loop.py:6827 — duplicate ids lose the later call's
            // result; strict providers reject duplicates outright.) ChatResponse
            // carries an immutable list, so rebuild the response with the fixed copy.
            if (response.toolCalls() != null && response.toolCalls().size() > 1) {
                List<ToolCall> fixed = new ArrayList<>(response.toolCalls());
                if (ToolCallValidator.uniquifyToolCallIds(fixed) > 0) {
                    response = response.hasContent()
                        ? ChatResponse.textAndToolCalls(response.content(), fixed)
                        : ChatResponse.toolCalls(fixed);
                }
            }

            // ── Commentary emission (parity with Hermes _emit_interim_assistant_message) ──
            // When the LLM returns BOTH text AND tool calls, the text is "commentary" —
            // an interim assistant message shown to the user before tool execution.
            // In the streaming path, the text was already shown via onToken callbacks,
            // so we emit a "commentary" event with alreadyStreamed=true to signal the
            // gateway to issue a segment break (visual separator), not a duplicate message.
            if (properties.isCommentaryEnabled() && response.hasContent() && response.hasToolCalls()) {
                eventHelper().send(emitter, new StreamEvent("commentary", response.content(), null, null), streamCtx);
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
                eventHelper().send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }

            // P-02: run the SAME validation pipeline the sync path runs before
            // dispatching anything — the SSE loop previously executed tool
            // calls straight from the model response without name/JSON
            // validation, delegate cap/dedupe or truncation abort.
            java.util.Set<String> registeredToolNames = new java.util.HashSet<>();
            for (ToolDefinition td : tools) {
                registeredToolNames.add(td.name());
            }
            com.azhukov.agent.core.agent.ToolBatchPipeline.PipelineResult pipeline =
                toolBatchPipeline.prepare(response.toolCalls(), registeredToolNames, turnIndex);
            if (pipeline.truncatedArgs()) {
                log.warn("Truncated tool call arguments in stream — aborting turn (session {})", session.id());
                eventHelper().send(emitter, new StreamEvent("error", null, null,
                    "Response truncated due to output length limit"), streamCtx);
                eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                emitter.complete();
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }
            if (!pipeline.syntheticResults().isEmpty()) {
                turnMessages.addAll(pipeline.syntheticResults());
            }

            TurnState turnState = turnStateManager.getOrStart(session.id(), 1);
            int toolBatchStart = turnMessages.size();
            for (ToolCall call : pipeline.executableCalls()) {
                // Skill-creation nudge (Hermes parity: conversation_loop.py:1977-1980):
                // each tool-calling iteration counts toward the skill review threshold;
                // skill_manage itself resets it (handled in resetNudgeCounters).
                if (memoryNudgeManager != null) {
                    try {
                        memoryNudgeManager.incrementSkillIters(session.id());
                    } catch (Exception e) {
                        log.debug("Skill iter increment failed for {}: {}", session.id(), e.getMessage());
                    }
                }
                // Check interrupt before each tool execution
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Streaming turn cancelled by interrupt before tool {} for session {}",
                        call.name(), session.id());
                    eventHelper().send(emitter, new StreamEvent("interrupted", null, null, "Turn cancelled by user."), streamCtx);
                    eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
                    emitter.complete();
                    if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                    return;
                }
                eventHelper().send(emitter, new StreamEvent("tool_start", null,
                    java.util.List.of(new com.azhukov.agent.core.model.ToolCall(call.id(), call.name(), call.arguments())),
                    null, null, null, null, call.name(), null), streamCtx);

                long toolStart = System.currentTimeMillis();
                ToolResult result = toolExecutionService.execute(
                    call.name(), call.id(), call.arguments(), null, session, turnState);
                long duration = System.currentTimeMillis() - toolStart;

                budget = iterationBudget.recordToolExecution(budget, call.name(), duration);
                // Hermes parity (conversation_loop.py:7277-7280): when the ONLY
                // tool(s) called in this iteration were execute_code, the tool
                // executions are refunded — programmatic calls are cheap RPCs
                // and must not starve the per-turn budget.
                boolean onlyExecuteCodeThisIteration = response.toolCalls().stream()
                    .allMatch(tc -> "execute_code".equals(tc.name()));
                if (onlyExecuteCodeThisIteration && "execute_code".equals(call.name())) {
                    budget = iterationBudget.refundToolExecution(budget);
                }

                String resultPreview = eventHelper().formatResultPreview(result);
                eventHelper().send(emitter, new StreamEvent("tool_result", null, null, null,
                    null, null, null, call.name(), resultPreview), streamCtx);

                String toolResultContent = toolResultFormatter.formatResult(result);
                turnMessages.add(Message.toolResult(call.pairingId(), toolResultContent, turnIndex));
            }

            // Aggregate budget comes AFTER the batch, as in Hermes
            // enforce_turn_budget: many individually-small results can still
            // overflow the model context together.
            java.util.List<Message> batchToolMessages = new java.util.ArrayList<>();
            for (int batchIndex = toolBatchStart; batchIndex < turnMessages.size(); batchIndex++) {
                Message message = turnMessages.get(batchIndex);
                if (message.role() == com.azhukov.agent.core.model.Role.TOOL) {
                    batchToolMessages.add(message);
                }
            }
            java.util.List<Message> boundedToolMessages = toolExecutionService.enforceToolResultBudget(batchToolMessages);
            if (!boundedToolMessages.isEmpty()) {
                for (int batchIndex = toolBatchStart, toolIndex = 0; batchIndex < turnMessages.size(); batchIndex++) {
                    if (turnMessages.get(batchIndex).role() == com.azhukov.agent.core.model.Role.TOOL) {
                        turnMessages.set(batchIndex, boundedToolMessages.get(toolIndex++));
                    }
                }
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

            // Proactive compression check after tool batch (Hermes parity).
            // Hermes checks should_compress at 50% threshold after every tool batch.
            // The non-streaming path uses TurnExecutor.checkProactiveCompression.
            // Without this, context grows unbounded through tool results until
            // a reactive CONTEXT_OVERFLOW error or provider 400 rejection.
            if (contextEngine instanceof DefaultContextEngine dce) {
                if (dce.shouldCompressPreflight(turnMessages)) {
                    int targetChars = properties.getContext().getTargetTokens() * 4;
                    List<Message> compressed = dce.getContextCompressor()
                        .compress(turnMessages, targetChars);
                    int charsBefore = turnMessages.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
                    int charsAfter = compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum();
                    // Hermes parity: judge compression by CONTENT REDUCTION (bytes), not message
                    // count — a protected-tail pressure pass prunes bulky messages in place,
                    // keeping the count equal while cutting chars (the live 'from 2 to 2' no-op
                    // class, 2026-08-23).
                    if (compressed.size() < turnMessages.size() || charsAfter < charsBefore) {
                        log.info("Proactive compression after tool batch: {} → {} messages, {} → {} chars for session {}",
                            turnMessages.size(), compressed.size(), charsBefore, charsAfter, session.id());
                        turnMessages.clear();
                        turnMessages.addAll(compressed);
                        persistedUpTo = turnMessages.size();
                    }
                }
            }

            turnIndex++;
          } catch (Exception outerEx) {
            // P-08 (Hermes #92450): bound escaped exceptions per turn; at the
            // cap terminate with an error event instead of spinning forever.
            if (outerErrors.recordAndCheckExhausted()) {
                log.error("Streaming outer error budget exhausted ({}/{}): {}",
                    outerErrors.count(), outerErrors.cap(), outerEx.getMessage(), outerEx);
                eventHelper().send(emitter, new StreamEvent("error", null, null,
                    outerErrors.exhaustedMessage(outerEx.getMessage())), streamCtx);
                eventHelper().safeCompleteWithError(emitter,
                    new RuntimeException(outerErrors.exhaustedMessage(outerEx.getMessage())));
                if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
                return;
            }
            log.warn("Streaming outer loop error {}/{} — continuing turn: {}",
                outerErrors.count(), outerErrors.cap(), outerEx.getMessage());
          }
        }

        // Max turns reached
        eventHelper().send(emitter, new StreamEvent("token", "Reached maximum turns without completion.", null, null), streamCtx);
        eventHelper().send(emitter, new StreamEvent("done", null, null, null), streamCtx);
        emitter.complete();
        if (persisted.compareAndSet(false, true)) persistTurn(session, turnMessages, isNew, midTurnPersistenceCallback != null ? persistedUpTo : 0);
        } finally {
            // Clean up interrupt token map entry and ThreadLocal after stream completion
            interruptToken.remove(session.id());
            InterruptToken.clearCurrentSessionId();
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

    /**
     * Hermes parity (turn_context.py:707): the memory nudge only counts when
     * the memory toolset is actually available to the session (tool not
     * disabled via request). Subagents and memory-disabled sessions never
     * accumulate review counters.
     */
    private boolean toolsetsIncludeMemory(ChatRequest request) {
        if (request != null && request.disabledTools() != null
                && request.disabledTools().contains("memory")) {
            return false;
        }
        return properties.getSkills().getDefaultToolsets().contains("memory");
    }

    private int estimateResponseTokens(String content, List<ToolCall> toolCalls) {
        // c2: delegate to TurnExecutor's shared static helper
        return com.azhukov.agent.core.agent.TurnExecutorUtils.estimateResponseTokens(content, toolCalls);
    }

    private void persistTurn(Session session, List<Message> turnMessages, boolean isNew) {
        persistTurn(session, turnMessages, isNew, 0);
    }

    private void persistTurn(Session session, List<Message> turnMessages, boolean isNew, int fromIndex) {
        // Deleted-session guard (same race as MidTurnPersistenceService): the
        // session row can be removed while the turn is still streaming; a
        // pre-check keeps the FK violation out of the journal entirely.
        if (!sessionRepository.existsById(session.id())) {
            log.debug("persistTurn skipped: session {} no longer exists", session.id());
            return;
        }
        // Hermes parity (message_sanitization.py:296): close interrupted tool
        // sequence before persisting. If the last message is a TOOL result
        // (interrupt/error/budget cut the turn short), append a synthetic
        // assistant message so the persisted history doesn't end on tool→user
        // (role-alternation violation → Gemini/Claude 400, #48879).
        TurnFinalizer.closeInterruptedToolSequence(turnMessages, TurnExitReason.INTERRUPTED);
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

    private Session resolveRotatedSession(Session session) {
        if (contextEngine instanceof DefaultContextEngine dce) {
            Optional<Session> rotated = dce.resolveRotatedSession(session);
            if (rotated.isPresent()) {
                Session ns = rotated.get();
                log.info("Switching to rotated session: old={}, new={}", session.id(), ns.id());
                return ns;
            }
        }
        return session;
    }

    /**
     * Hermes parity: jittered_backoff (base 5s, cap 60s) with ±25% jitter
     * (conversation_loop.py:7659, agent/jitter util semantics).
     */
    static long jitteredBackoffMs(int attempt) {
        return com.azhukov.agent.core.agent.ResponseRecoveryPolicy.jitteredBackoffMs(
            attempt, EMPTY_BACKOFF_BASE_MS, EMPTY_BACKOFF_CAP_MS);
    }

    /** Parameterised variant — base/cap come from config so tests can shorten the wait. */
    static long jitteredBackoffMs(int attempt, long baseMs, long capMs) {
        return com.azhukov.agent.core.agent.ResponseRecoveryPolicy.jitteredBackoffMs(attempt, baseMs, capMs);
    }

    /**
     * Hermes parity: sleep in small increments so interrupts (user cancel) are honoured
     * mid-backoff (conversation_loop.py:7685-7700 "sleep in small increments").
     *
     * @return true if the full duration elapsed, false if interrupted/cancelled
     */
    private boolean interruptibleSleep(long totalMs, UUID sessionId, InterruptToken token) {
        long deadline = System.currentTimeMillis() + totalMs;
        while (System.currentTimeMillis() < deadline) {
            if (token != null && token.isCancelled(sessionId)) {
                return false;
            }
            long step = Math.min(250L, deadline - System.currentTimeMillis());
            if (step <= 0) break;
            try {
                Thread.sleep(step);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

}