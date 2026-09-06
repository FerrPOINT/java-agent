package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.credential.CredentialPool;
import com.azhukov.agent.client.credential.PooledCredential;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import org.springframework.beans.factory.annotation.Autowired;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.budget.IterationBudget.TurnSnapshot;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.context.DefaultContextReferenceService;
import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryManager;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.ReviewSummary;
import com.azhukov.agent.core.model.ChatResponse;

// c1: think-block helpers consolidated in ThinkBlockProcessor after FallbackModelCaller extraction
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.containsAnyThinkTag;
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.hasContentAfterThinkBlock;
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.hasIncompleteScratchpad;
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.isThinkingBudgetExhausted;
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.stripThinkBlocksFromString;
import static com.azhukov.agent.core.agent.TurnExecutor.ContentPolicyException;
import com.azhukov.agent.core.agent.ResponseRecoveryPolicy;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolCallValidator;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolParallelSafety;
import com.azhukov.agent.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAgentRuntime implements AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    // Verify-on-stop guard (Hermes parity: verification_stop.py)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VerifyOnStopGuard verifyOnStopGuard;
    // Coding workspace snapshot for verify commands
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.azhukov.agent.core.context.CodingWorkspaceSnapshot codingWorkspaceSnapshot;
    private final PromptBuilder promptBuilder;
    private final ContextEngine contextEngine;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final IterationBudget iterationBudget;
    private final MessageSanitizer messageSanitizer;
    private final ContextReferenceService contextReferenceService;
    private final AgentProperties properties;
    private final UserInputSanitizer inputSanitizer;
    private final ToolCallGuardrail guardrail;
    private final TurnStateManager turnStateManager;
    private final BackgroundReviewService backgroundReviewService;
    private final InterruptToken interruptToken;
    private final TurnFinalizer turnFinalizer;
    private final SteerBuffer steerBuffer;
    private final ErrorClassifier errorClassifier;
    private final ContextCompressor contextCompressor;
    private final com.azhukov.agent.core.security.ApprovalQueue approvalQueue;
    private final com.azhukov.agent.core.security.ToolGuardrails toolGuardrails;
    private final MemoryManager memoryManager;
    private final TokenEstimator tokenEstimator;
    private final ToolResultFormatter toolResultFormatter;
    private final MidTurnPersistenceCallback midTurnPersistenceCallback;
    private final CommentaryCallback commentaryCallback;

    /** Per-turn fallback state. A runtime is a singleton, so this must never be shared across sessions. */
    private static final class TurnModelState {
        private final FallbackManager fallbackManager;
        private ModelClient activeClient;

        private TurnModelState(FallbackManager fallbackManager, ModelClient primaryClient) {
            this.fallbackManager = fallbackManager;
            this.activeClient = primaryClient;
        }
    }

    /** c1: extracted retry+fallback loop owner (lazy — plain fields, no ctor churn). */
    private volatile FallbackModelCaller fallbackModelCaller;
    private final ReentrantLock turnLock = new ReentrantLock();

    /**
     * DEBT-2 (M32): turn usage sink — fallback-model tokens must be billed like primary.
     * Optional field injection (same pattern as McpOAuthManager) to avoid churning
     * the @RequiredArgsConstructor signature used positionally by ~22 tests.
     */
    @Autowired(required = false)
    private com.azhukov.agent.service.TurnUsageCollector turnUsageCollector;

    /** Wire the usage sink into the lazily-created fallback callers. */
    private java.util.function.Consumer<com.azhukov.agent.client.langchain4j.LangChain4jModelClient.Usage>
            fallbackUsageConsumer() {
        if (turnUsageCollector == null) return null;
        return usage -> turnUsageCollector.record(usage.promptTokens(), usage.completionTokens());
    }

    /** M2 fix: per-thread turn state; DefaultAgentRuntime is a singleton and different sessions run concurrently. */
    private final ThreadLocal<TurnModelState> turnModelState = new ThreadLocal<>();

    private FallbackModelCaller fallbackModelCaller() {
        FallbackModelCaller fmc = fallbackModelCaller;
        if (fmc == null) {
            turnLock.lock();
            try {
                if (fallbackModelCaller == null) {
                    fallbackModelCaller = new FallbackModelCaller(
                        errorClassifier, properties, contextCompressor, contextEngine);
                    fallbackModelCaller.setUsageConsumer(fallbackUsageConsumer());
                }
                fmc = fallbackModelCaller;
            } finally {
                turnLock.unlock();
            }
        }
        return fmc;
    }

    // Shared daemon executor for memory sync — avoids creating a new executor every turn
    // Virtual threads are daemon by default in Java 25
    private final ExecutorService memorySyncExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("memory-sync-", 0).factory());

    // ── c2: canonical shared turn-execution owner ──────────────────────────
    // TurnExecutor owns the tool-batch dispatch (approval gate, subagent
    // auto-deny, /yolo bypass, steer injection, budget enforcement,
    // execute_code refund) and the budget-exhaustion summary. Injected via
    // optional setter to keep the @RequiredArgsConstructor signature stable
    // for the ~14 positional test constructors; lazily built from existing
    // dependencies when Spring doesn't wire it (unit tests).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TurnExecutor turnExecutor;

    TurnExecutor turnExecutor() {
        TurnExecutor te = turnExecutor;
        if (te == null) {
            te = new TurnExecutor(
                errorClassifier, properties, contextCompressor, contextEngine,
                toolExecutionService, toolResultFormatter, tokenEstimator,
                interruptToken, approvalQueue, toolGuardrails,
                memoryNudgeManagerOrNull(), steerBuffer);
            turnExecutor = te;
        }
        return te;
    }

    private MemoryNudgeManager memoryNudgeManagerOrNull() {
        // The sync runtime keeps its own nudge maps (hydrated from history in
        // run()); TurnExecutor's MemoryNudgeManager reference is used only for
        // counter resets on skill_manage/memory calls, which the sync path
        // performs inline on its own maps. Null keeps TurnExecutor from
        // double-resetting a manager this runtime doesn't drive.
        return null;
    }

    // c2: shared pre-execution validation pipeline (P-02). Optional injection
    // keeps the @RequiredArgsConstructor signature stable for positional tests.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ToolBatchPipeline toolBatchPipeline;

    ToolBatchPipeline toolBatchPipeline() {
        ToolBatchPipeline p = toolBatchPipeline;
        if (p == null) {
            p = new ToolBatchPipeline();
            toolBatchPipeline = p;
        }
        return p;
    }

    // M17 executor removed — parallel tool execution now runs on TurnExecutor's
    // shared executor via executeToolBatch (c2).

    // Nudge counters — per-session, mirroring Hermes _turns_since_memory / _iters_since_skill.
    // Memory review fires every N user turns; skill review fires every M tool-calling iterations.
    // Skill counter resets to 0 whenever skill_manage is actually called.
    private final ConcurrentHashMap<UUID, AtomicInteger> turnsSinceMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> itersSinceSkill = new ConcurrentHashMap<>();

    // Per-session locks to prevent concurrent turns on the same session (parity with Hermes _session_locks).
    // Serializes turn execution so that messages, turn state, and DB writes don't interleave.
    private final ConcurrentHashMap<UUID, java.util.concurrent.locks.ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    @PreDestroy
    void shutdown() {
        // Finding 6.2: Use shutdownNow() to cancel pending tasks and log uncompleted ones,
        // preventing silent data loss on JVM shutdown.
        List<Runnable> pending = memorySyncExecutor.shutdownNow();
        if (!pending.isEmpty()) {
            log.warn("Memory sync: {} pending task(s) cancelled on shutdown (data may be lost)", pending.size());
        }
        try {
            if (!memorySyncExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                List<Runnable> stillPending = memorySyncExecutor.shutdownNow();
                if (!stillPending.isEmpty()) {
                    log.warn("Memory sync: {} task(s) still running after forced shutdown", stillPending.size());
                }
            }
        } catch (InterruptedException e) {
            memorySyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isSessionBusy(UUID sessionId) {
        java.util.concurrent.locks.ReentrantLock lock = sessionLocks.get(sessionId);
        return lock != null && lock.isLocked();
    }

    @Override
    public ChatResponse run(List<Message> messages, List<ToolDefinition> tools) {
        return run(messages, tools, ModelRequestOptions.empty());
    }

    @Override
    public ChatResponse run(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options) {
        List<Message> sanitized = messageSanitizer.sanitize(messages);
        List<Message> context = contextEngine.prepareContext(
            Session.create("openai-user", "openai-compatible", ""), sanitized);
        TurnModelState modelState = turnModelState.get();
        ModelClient client = modelState != null && modelState.activeClient != null
            ? modelState.activeClient : modelClient;
        return client.complete(HistorySanitizer.sanitizeForModelRequest(context), tools,
            options != null ? options : ModelRequestOptions.empty());
    }

    @Override
    public TurnResult runTurn(Session session, String userInput, List<String> references,
                              ModelRequestOptions options) {
        ModelRequestOptions effectiveOptions = options != null ? options : ModelRequestOptions.empty();
        // Runtime callers include delegate and gateway paths that do not pass
        // through AgentRuntimeService, so this remains the canonical safety lock.
        UUID sid = session.id();
        java.util.concurrent.locks.ReentrantLock lock = sessionLocks.computeIfAbsent(
            sid, k -> new java.util.concurrent.locks.ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for session lock", e);
        }
        if (!acquired) {
            throw new IllegalStateException(
                "Session " + sid + " is busy (another turn is in progress). Retry later.");
        }
        try {
            return runTurnInternal(session, userInput, references, effectiveOptions);
        } finally {
            lock.unlock();
            // rev-81: clear the ThreadLocal turn-history snapshot — virtual threads
            // are reused, and a stale snapshot leaks memory (full lineage history
            // retained on the thread).
            turnModelState.remove();
            if (contextEngine instanceof com.azhukov.agent.core.context.DefaultContextEngine dce) {
                dce.evictTurnCache(sid);
            }
        }
    }

    private TurnResult runTurnInternal(Session session, String userInput, List<String> references,
                                       ModelRequestOptions options) {
        UUID sessionIdUuid = session.id();
        String sessionId = sessionIdUuid.toString();
        // P-05: foreground work supersedes any delayed/in-flight review for
        // this session before it can call the model or write memory.
        if (backgroundReviewService != null) {
            backgroundReviewService.cancelForNewForegroundTurn(sessionIdUuid);
        }
        guardrail.reset(sessionIdUuid);
        // Clear stale cancellation from an earlier child turn before this new turn starts.
        if (interruptToken != null) {
            interruptToken.reset(sessionIdUuid);
        }
        toolExecutionService.resetLoopGuardrailForTurn();
        turnStateManager.clear(sessionIdUuid);
        // Clear any pending steer from a previous turn (parity with Hermes
        // _drain_pending_steer clearing on turn start).
        if (steerBuffer != null) {
            steerBuffer.clear(sessionIdUuid);
        }
        TurnSnapshot budget = iterationBudget.startTurn(sessionIdUuid);
        String safeInput = inputSanitizer.sanitize(userInput);

        // ── Fallback chain initialization (parity with Hermes turn_context.py) ──
        // At the start of each turn, restore the primary model if a fallback was
        // activated during the previous turn. Then initialize the fallback manager
        // with the current primary config and the configured fallback chain.
        // M2 fix: this state is per-turn and never shared across sessions.
        turnModelState.set(new TurnModelState(
            new FallbackManager(
                properties.getFallbackChain(),
                properties.getModel().getProvider(),
                properties.getModel().getModelName(),
                properties.getModel().getBaseUrl(),
                properties.getModel().getApiKey()),
            modelClient));

        // Resolve effective toolsets: session metadata override (from DelegateTaskTool)
        // takes priority, then the configured default toolsets.
        Set<String> effectiveToolsets;
        String toolsetsMeta = session.getMetadata("delegation_toolsets");
        if (toolsetsMeta != null && !toolsetsMeta.isBlank()) {
            effectiveToolsets = new HashSet<>(Arrays.asList(toolsetsMeta.split(",")));
        } else {
            effectiveToolsets = new HashSet<>(properties.getSkills().getDefaultToolsets());
        }

        // Nudge: increment per-session memory turn counter
        // H6: Only increment if the memory toolset is actually available to the session.
        // M8: On first turn for a session (restart), hydrate the counter from prior
        // user turns in the conversation history so the nudge interval is preserved
        // across restarts. Mirrors Hermes which initializes _turns_since_memory from
        // the persisted conversation length on session load.
        if (effectiveToolsets.contains("memory")) {
            int memNudge = properties.getMemory().getNudgeInterval();
            AtomicInteger memCounter = turnsSinceMemory.computeIfAbsent(sessionIdUuid, k -> {
                // M8: Hydrate from history — count prior user turns and initialize
                // to priorUserTurns % nudgeInterval so the counter reflects the
                // actual conversation progress.
                // Finding 5.2: Use countPriorUserMessages instead of the expensive
                // prepareContext call that triggers full context building and
                // potentially compression side effects.
                if (memNudge > 0) {
                    try {
                        long priorUserTurns = contextEngine.countPriorUserMessages(sessionIdUuid);
                        int initial = (int) (priorUserTurns % memNudge);
                        log.debug("M8: Hydrated turnsSinceMemory for session {} from history: {} prior user turns, initial={}",
                            sessionIdUuid, priorUserTurns, initial);
                        return new AtomicInteger(initial);
                    } catch (Exception e) {
                        log.debug("M8: Failed to hydrate turnsSinceMemory from history: {}", e.getMessage());
                    }
                }
                return new AtomicInteger(0);
            });
            memCounter.incrementAndGet();
        }

        // S14: MemoryManager lifecycle hook — on_turn_start
        if (memoryManager != null) {
            try {
                memoryManager.onTurnStart(sessionId, safeInput);
            } catch (Exception e) {
                log.warn("MemoryManager onTurnStart failed: {}", e.getMessage());
            }
        }

        TurnState turnState = turnStateManager.getOrStart(sessionIdUuid, 1);
        List<Message> turnMessages = new ArrayList<>();
        // Pass system prompt override from session metadata (set by DelegateTaskTool)
        String systemPromptOverride = session.getMetadata("system_prompt_override");
        // PR-3 parity: a persisted per-session system prompt (session.systemPrompt())
        // overrides the default base prompt, same priority as a delegation override.
        String persistedPrompt = session.systemPrompt();
        String effectiveOverride = (systemPromptOverride != null && !systemPromptOverride.isBlank())
            ? systemPromptOverride
            : (persistedPrompt != null && !persistedPrompt.isBlank() ? persistedPrompt : null);
        if (effectiveOverride != null
                && promptBuilder instanceof DefaultPromptBuilder dpb) {
            turnMessages.add(dpb.buildSystemMessage(session, effectiveOverride));
        } else {
            turnMessages.add(promptBuilder.buildSystemMessage(session));
        }
        if (references != null && !references.isEmpty()) {
            var resolvedRefs = contextReferenceService.resolve(references);
            Optional<String> refContent;
            if (contextReferenceService instanceof DefaultContextReferenceService defaultSvc) {
                refContent = defaultSvc.loadContentWithBudget(resolvedRefs);
            } else {
                // Fallback: load content individually without budget enforcement
                StringBuilder sb = new StringBuilder();
                for (var ref : resolvedRefs) {
                    contextReferenceService.loadContent(ref).ifPresent(content ->
                        sb.append("[").append(ref.displayName()).append("]\n").append(content).append("\n\n"));
                }
                refContent = sb.length() > 0 ? Optional.of(sb.toString().trim()) : Optional.empty();
            }
            if (refContent.isPresent()) {
                turnMessages.add(Message.user(safeInput + "\n\n--- References ---\n\n" + refContent.get()));
            } else {
                turnMessages.add(Message.user(safeInput));
            }
        } else {
            turnMessages.add(Message.user(safeInput));
        }

        // Toolsets already resolved above (before memory nudge counter).
        List<ToolDefinition> tools = toolRegistry.getDefinitions(effectiveToolsets);
        // Delegation deny-list: subtract blocked TOOL names after composite
        // expansion (Hermes parity — mixed bundles like hermes-cli must not leak
        // delegate_task/clarify/memory/send_message/cronjob to child sessions).
        String blockedMeta = session.getMetadata("delegation_blocked_tools");
        if (blockedMeta != null && !blockedMeta.isBlank()) {
            Set<String> denied = new HashSet<>(Arrays.asList(blockedMeta.split(",")));
            tools = tools.stream()
                .filter(td -> !denied.contains(td.name()))
                .collect(java.util.stream.Collectors.toList());
        }

        // Resolve effective max turns: session metadata override (from DelegateTaskTool)
        // takes priority when positive, then the configured core.max-turns.
        int maxTurns = properties.getCore().getMaxTurns();
        String maxTurnsMeta = session.getMetadata("delegation_max_turns");
        if (maxTurnsMeta != null && !maxTurnsMeta.isBlank()) {
            try {
                int override = Integer.parseInt(maxTurnsMeta.trim());
                if (override > 0) {
                    maxTurns = override;
                }
            } catch (NumberFormatException e) {
                log.debug("Invalid delegation_max_turns metadata '{}', ignoring", maxTurnsMeta);
            }
        }
        int turnIndex = 1;

        // Prefetch relevant memories before the turn (A7)
        try {
            memoryProvider.prefetch(safeInput, sessionId);
        } catch (Exception e) {
            log.warn("Memory prefetch failed for session {}: {}", sessionId, e.getMessage());
        }

        TurnResult result = null;
        String pendingSteer = null;
        try {
        result = runTurnLoop(session, turnMessages, tools, maxTurns, turnIndex, budget, turnState, sessionId, sessionIdUuid, options, effectiveToolsets);
        } finally {
            // P8 parity (turn_finalizer.py:756): a steer arriving after the last
            // model/tool boundary must be handed to the caller for the next turn,
            // never cleared as a lost in-flight note. consume() atomically drains it.
            if (steerBuffer != null) {
                pendingSteer = steerBuffer.consume(sessionIdUuid);
            }
            // Clean up per-session guardrail state to prevent memory leaks (REM-2)
            guardrail.reset(sessionIdUuid);
            // S14: MemoryManager — sync turn data + queue prefetch for next turn
            if (memoryManager != null && memoryManager.hasProviders()) {
                try {
                    final List<Message> messagesToSync = List.copyOf(turnMessages);
                    memoryManager.syncAll(sessionId, messagesToSync);
                    memoryManager.queuePrefetchAll(safeInput, sessionId);
                } catch (Exception e) {
                    log.warn("MemoryManager sync/queue failed for session {}: {}", sessionId, e.getMessage());
                }
            } else {
                // Fallback: direct memory provider sync (legacy path)
                try {
                    final List<Message> messagesToSync = List.copyOf(turnMessages);
                    memorySyncExecutor.submit(() -> {
                        try {
                            memoryProvider.syncTurn(sessionId, messagesToSync);
                        } catch (Exception e) {
                            log.warn("Memory syncTurn failed for session {}: {}", sessionId, e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.warn("Failed to submit memory syncTurn for session {}: {}", sessionId, e.getMessage());
                }
            }
        }
        // ── Primary restoration after turn completes (parity with Hermes turn_context.py) ──
        // If fallback was activated during the turn, restore the primary model so the
        // next turn starts fresh. The restorePrimary() call is also made at the start
        // of the next turn as a safety net.
        TurnModelState modelState = turnModelState.get();
        if (modelState != null && modelState.fallbackManager.isFallbackActivated()) {
            modelState.fallbackManager.restorePrimary();
            modelState.activeClient = modelClient;
        }
        // Preserve a late steer as a first-class handoff to the caller. The
        // gateway turns this into the next queued user event after this turn exits.
        if (result != null && pendingSteer != null && !pendingSteer.isBlank()) {
            return new TurnResult(result.messages(), result.completed(), result.error(), pendingSteer);
        }
        return result;
    }

    private TurnResult runTurnLoop(Session session, List<Message> turnMessages, List<ToolDefinition> tools,
                                   int maxTurns, int turnIndex, TurnSnapshot budget, TurnState turnState,
                                   String sessionId, UUID sessionIdUuid, ModelRequestOptions options,
                                   Set<String> effectiveToolsets) {
        // Thinking-specific retry counters — reset per turn (parity with Hermes
        // _thinking_prefill_retries / _incomplete_scratchpad_retries).
        // These track recovery attempts across loop iterations within a single turn.
        int thinkingPrefillRetries = 0;
        int incompleteScratchpadRetries = 0;
        // c2: shared recovery counters (ResponseRecoveryPolicy) — same budgets as streaming
        int lengthContinueRetries = 0;
        int droppedToolcallRetries = 0;
        int truncatedToolCallRetries = 0;
        StringBuilder truncatedParts = new StringBuilder();
        // Empty response retry counter (parity with Hermes _empty_content_retries: max 3)
        int retryStateEmptyResponse = 0;
        // Hermes parity: wall-clock run-budget wrap-up notice latch (one-shot per turn)
        final long turnStartMillis = System.currentTimeMillis();
        // c2: latch holder for the shared TurnExecutorUtils.maybeInjectRunBudgetWrapup
        final int[] runBudgetWrapupLatch = {0};
        // Hermes: whether the PREVIOUS model round landed tool calls — drives the
        // empty-recovery nudge choice (post-tool stub vs plain backoff).
        boolean lastResponseHadToolCalls = false;
        // R4 (Hermes empty_response_guard): deterministic-empty detection —
        // ≥2 consecutive zero-output attempts with identical signature skip retries.
        EmptyResponseGuard emptyGuard = new EmptyResponseGuard();
        // P-08 (Hermes #92450): bound escaped post-model exceptions per turn.
        OuterErrorBudget outerErrors = new OuterErrorBudget(maxTurns);

        // P1-5: Mid-turn persistence cursor — tracks how many messages have been
        // flushed to the database. After each tool batch, new messages (assistant
        // with tool calls + tool results) are persisted immediately, mirroring
        // Hermes' _persist_session / _flush_messages_to_session_db pattern.
        // If the JVM crashes mid-turn, all progress up to the last batch is preserved.
        int persistedUpTo = turnMessages.size();

        for (int i = 0; i < maxTurns; i++) {
          try {
            // M10: Increment skill iteration counter at the TOP of the loop
            // (before model call) so it counts every iteration, not just
            // tool-calling iterations. Mirrors Hermes _iters_since_skill which
            // is incremented at the start of each loop iteration.
            // H6: Only increment if the skills toolset is actually available.
            if (effectiveToolsets.contains("skills")) {
                itersSinceSkill.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).incrementAndGet();
            }

            if (guardrail.isHalted(session.id())) {
                turnMessages.add(Message.assistant("Turn halted by guardrails.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.GUARDRAIL_HALTED);
                }
                return new TurnResult(turnMessages, true, null);
            }
            if (iterationBudget.isExhausted(budget)) {
                log.warn("Iteration budget exhausted for session {} after {} model calls, {} tool executions",
                    session.id(), budget.modelCalls(), budget.toolExecutions());
                // Mirrors Hermes _handle_max_iterations: make one extra toolless LLM call
                // asking the model to summarise what it accomplished, instead of just
                // printing a raw "budget exhausted" message. c2: shared owner in
                // TurnExecutor.
                TurnModelState modelState = turnModelState.get();
                ModelClient summaryClient = modelState != null && modelState.activeClient != null
                    ? modelState.activeClient : modelClient;
                String summary = turnExecutor().requestBudgetExhaustionSummary(
                    summaryClient, session, turnMessages, options);
                String budgetMsg = summary != null && !summary.isBlank()
                    ? summary
                    : "⚠️ Iteration budget exhausted (" + budget.modelCalls()
                        + "/" + properties.getBudget().getMaxModelCallsPerTurn() + ")";
                turnMessages.add(Message.assistant(budgetMsg, turnIndex));
                // H7: Fire background review on budget-exhausted path too.
                boolean interrupted = interruptToken != null && interruptToken.isCancelled(session.id());
                triggerNudgedBackgroundReview(session, turnMessages, interrupted);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, true, TurnExitReason.BUDGET_EXHAUSTED);
                }
                return new TurnResult(turnMessages, true, null);
            }

            List<Message> context = contextEngine.prepareContext(session, turnMessages);
            session = resolveRotatedSession(session);
            // Hermes parity: pre-API-call /steer drain (conversation_loop.py:2104-2153).
            // c2: shared owner in TurnExecutorUtils — previously a verbatim copy
            // in both loops.
            if (steerBuffer != null) {
                String preApiSteer = steerBuffer.consume(session.id());
                if (preApiSteer != null
                        && !TurnExecutorUtils.injectPreApiSteer(context, preApiSteer, session.id())) {
                    steerBuffer.steer(session.id(), preApiSteer);
                }
            }
            // Hermes parity: wall-clock run-budget wrap-up notice (conversation_loop.py:2154-2172).
            // c2: shared owner in TurnExecutorUtils.
            TurnExecutorUtils.maybeInjectRunBudgetWrapup(context,
                properties.getBudget().getRunBudgetSeconds(), turnStartMillis,
                runBudgetWrapupLatch);
            ChatResponse response;
            try {
                long callStart = System.currentTimeMillis();
                response = callModelWithRetry(context, tools, session, options);
                // P-06: the retry flag applies to exactly one model call
                com.azhukov.agent.client.langchain4j.EmptyRetryCacheBypass.clear();
                int duration = (int) (System.currentTimeMillis() - callStart);
                int estimatedInput = tokenEstimator.estimateTokens(context);
                int estimatedOutput = estimateResponseTokens(response);
                budget = iterationBudget.recordModelCall(budget, estimatedInput, estimatedOutput);
                turnState.recordModelCall();
                log.debug("Turn {} model returned in {} ms: toolCalls={}, content length={}",
                    i, duration, response.toolCalls() != null ? response.toolCalls().size() : 0,
                    response.content() != null ? response.content().length() : 0);
            } catch (ContentPolicyException e) {
                // ── Part F: Content policy handling — user-friendly message, terminal ──
                log.warn("Content policy block: {}", e.getMessage());
                String policyMsg = e.getMessage();
                if (!policyMsg.contains(ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT)) {
                    policyMsg = policyMsg + "\n\n" + ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT;
                }
                turnMessages.add(Message.assistant(policyMsg, turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.CONTENT_POLICY);
                }
                return new TurnResult(turnMessages, true, null);
            } catch (Exception e) {
                log.error("Model call failed after retries", e);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MODEL_CALL_FAILED);
                }
                return TurnResult.error("Model call failed: " + e.getMessage());
            }

            // ── Incomplete scratchpad handling (parity with Hermes conversation_loop.py:3584) ──
            // If response contains <REASONING_SCRATCHPAD> but NOT </REASONING_SCRATCHPAD>,
            // the model ran out of output tokens mid-reasoning — retry up to 2 times.
            if (hasIncompleteScratchpad(response.content())) {
                incompleteScratchpadRetries++;
                log.warn("Incomplete <REASONING_SCRATCHPAD> detected (opened but never closed), retry {}/2",
                    incompleteScratchpadRetries);
                if (incompleteScratchpadRetries <= 2) {
                    // Don't add the broken message, just retry
                    turnIndex--;
                    continue;
                } else {
                    // Max retries exhausted — return partial error
                    log.error("Max retries (2) for incomplete scratchpad. Saving as partial.");
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INCOMPLETE_SCRATCHPAD);
                    }
                    return TurnResult.error("Incomplete REASONING_SCRATCHPAD after 2 retries");
                }
            }

            // Reset incomplete scratchpad counter on clean response
            incompleteScratchpadRetries = 0;

            // Hermes parity (conversation_loop.py:3606-3692): a successful HTTP
            // response may still carry a content_filter finish reason. Treat it
            // as a terminal policy refusal, preserving any provider refusal text
            // and adding the shared actionable recovery hint.
            if ("CONTENT_FILTER".equals(response.finishReason()) && !response.hasToolCalls()) {
                String policyMsg = (response.hasContent() ? response.content().strip() + "\n\n" : "")
                    + ResponseRecoveryPolicy.CONTENT_POLICY_RECOVERY_HINT;
                turnMessages.add(Message.assistant(policyMsg, turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.CONTENT_POLICY);
                }
                return new TurnResult(turnMessages, true, null);
            }

            // ── Truncated tool call recovery (Hermes parity: conversation_loop.py:3829-3860) ──
            // LENGTH finish_reason WITH tool calls: the model hit the output cap
            // mid-tool-call JSON. The arguments are truncated/incomplete and must
            // NOT be executed. Re-run the same API call with a boosted max_tokens
            // (2^attempt × base, capped at 32768) giving the model room to finish.
            if (ResponseRecoveryPolicy.isTruncatedToolCall(response.finishReason(), response.hasToolCalls())
                    && truncatedToolCallRetries < ResponseRecoveryPolicy.MAX_TRUNCATED_TOOL_CALL_RETRIES) {
                truncatedToolCallRetries++;
                int boostedMax = ResponseRecoveryPolicy.boostedMaxTokens(
                    properties.getModel().getMaxTokens(), truncatedToolCallRetries);
                log.warn("Truncated tool call detected (LENGTH + tool calls) — retrying with boosted max_tokens={} (attempt {}/{})",
                    boostedMax, truncatedToolCallRetries, ResponseRecoveryPolicy.MAX_TRUNCATED_TOOL_CALL_RETRIES);
                // Don't append the broken response; re-run from current context
                // with a boosted max_tokens so the model has room to complete the JSON.
                ModelRequestOptions boostedOptions = TurnExecutorUtils.withBoostedMaxTokens(options, boostedMax);
                context = contextEngine.prepareContext(session, turnMessages);
                session = resolveRotatedSession(session);
                response = callModelWithRetry(context, tools, session, boostedOptions);
                continue;
            }

            // Truncated tool call ceiling reached — refuse to execute incomplete arguments.
            if (ResponseRecoveryPolicy.isTruncatedToolCall(response.finishReason(), response.hasToolCalls())
                    && truncatedToolCallRetries >= ResponseRecoveryPolicy.MAX_TRUNCATED_TOOL_CALL_RETRIES) {
                log.warn("Truncated tool call after {} retries — refusing to execute incomplete tool arguments",
                    truncatedToolCallRetries);
                // Close the interrupted sequence as one assistant batch so
                // every recovery tool result still has its owning call on replay.
                turnMessages.add(Message.assistantWithToolCalls(response.content(), response.toolCalls(), turnIndex));
                for (ToolCall tc : response.toolCalls()) {
                    turnMessages.add(Message.toolResult(tc.pairingId(),
                        "[Truncated tool call — arguments were incomplete after "
                        + truncatedToolCallRetries + " retries. The tool was not executed.]",
                        turnIndex));
                }
                turnIndex++;
                context = contextEngine.prepareContext(session, turnMessages);
                session = resolveRotatedSession(session);
                response = callModelWithRetry(context, tools, session, options);
                continue;
            }

            // ── c2: LENGTH continuation (Hermes conversation_loop.py:3711-3775) ──
            // Sync-path parity with the streaming loop: the partial fragment is
            // ACCUMULATED (stitched) and the model is asked to continue; ceiling 4;
            // on exhaustion the stitched partial is KEPT, not discarded.
            if (ResponseRecoveryPolicy.isLengthContinuable(response, lengthContinueRetries)) {
                lengthContinueRetries++;
                log.info("LENGTH truncation detected — partial content ({} chars), continuation attempt {}/{}",
                    response.content().length(), lengthContinueRetries,
                    ResponseRecoveryPolicy.MAX_LENGTH_CONTINUATION_ATTEMPTS);
                String partialContent = response.content();
                // The continuation fragment and nudge must become part of the live
                // transcript. Building a throwaway context loses both at the next loop
                // iteration, so the model simply repeats the original request.
                if (partialContent != null && !partialContent.isEmpty()) {
                    truncatedParts.append(partialContent);
                    turnMessages.add(Message.assistant(partialContent, turnIndex));
                }
                turnMessages.add(Message.user(ResponseRecoveryPolicy.LENGTH_NUDGE));
                turnIndex++;
                session = resolveRotatedSession(session);
                continue;
            }
            if (("LENGTH".equals(response.finishReason()) || "incomplete".equalsIgnoreCase(response.finishReason()))
                    && response.hasContent() && !response.hasToolCalls()
                    && lengthContinueRetries >= ResponseRecoveryPolicy.MAX_LENGTH_CONTINUATION_ATTEMPTS) {
                String stitched = truncatedParts + response.content();
                log.warn("Response still truncated after {} continuation attempts — keeping partial ({} chars)",
                    lengthContinueRetries, stitched.length());
                response = ChatResponse.text(stitched, "STOP");
                truncatedParts.setLength(0);
                // fall through to the no-tool-calls completion path below
            } else if (truncatedParts.length() > 0 && !response.hasToolCalls() && response.hasContent()) {
                // c2: successful STOP after LENGTH continuations — join the stitched
                // fragments (streaming parity: response construction concatenates).
                response = ChatResponse.text(truncatedParts + response.content(), "STOP");
                truncatedParts.setLength(0);
            }

            // ── c2: Dropped tool-call recovery (Hermes conversation_loop.py:7918-7950) ──
            // finish_reason signalled tool calls but the parsed array is empty —
            // re-prompt (bounded, resets on any landed call) instead of finalizing.
            if (ResponseRecoveryPolicy.isDroppedToolcall(response, droppedToolcallRetries)) {
                droppedToolcallRetries++;
                log.warn("finish_reason=tool_calls with empty tool_calls array (narration only) — re-prompting (retry {}/{})",
                    droppedToolcallRetries, ResponseRecoveryPolicy.MAX_DROPPED_TOOLCALL_RETRIES);
                List<Message> droppedContext = new ArrayList<>(turnMessages);
                if (response.hasContent()) {
                    droppedContext.add(Message.assistant(response.content(), turnIndex));
                }
                droppedContext.add(Message.user(ResponseRecoveryPolicy.DROPPED_TOOLCALL_NUDGE));
                turnIndex++;
                context = contextEngine.prepareContext(session, droppedContext);
                session = resolveRotatedSession(session);
                continue;
            }

            // c2: a landed tool call resets the dropped-toolcall budget (Hermes :7133)
            droppedToolcallRetries =
                ResponseRecoveryPolicy.resetOnLandedToolCall(droppedToolcallRetries, response.hasToolCalls());

            if (!response.hasToolCalls()) {
                // ── Thinking-only prefill continuation (parity with Hermes conversation_loop.py:4136) ──
                // If content is ONLY think blocks with no visible text after stripping,
                // don't break the loop — add the assistant message as-is and continue.
                // The model sees its own reasoning on the next iteration and produces visible text.
                // Limit to 2 retries (matching Hermes _thinking_prefill_retries < 2).
                if (response.content() != null
                    && containsAnyThinkTag(response.content())
                    && !hasContentAfterThinkBlock(response.content())
                    && thinkingPrefillRetries < 2) {
                    thinkingPrefillRetries++;
                    log.info("Thinking-only response (no visible content) — prefilling to continue ({}/2)",
                        thinkingPrefillRetries);
                    // Add the assistant message as-is (with think blocks) so the model
                    // sees its own reasoning on the next iteration
                    turnMessages.add(Message.assistant(response.content(), turnIndex));
                    turnIndex++;
                    continue;
                }

                // ── Thinking-budget exhaustion detection (parity with Hermes conversation_loop.py:1521) ──
                // If the model spent ALL output tokens on reasoning and has none left for the
                // response, continuation retries are pointless. This fires AFTER prefill retries
                // are exhausted (thinkingPrefillRetries >= 2), matching Hermes where
                // _thinking_prefill_retries < 2 guards the prefill path and the exhausted
                // check is in the truncation handler.
                if (isThinkingBudgetExhausted(response) && thinkingPrefillRetries >= 2) {
                    String exhaustMsg = "⚠️ **Thinking Budget Exhausted**\n\n"
                        + "The model spent all its output tokens on reasoning "
                        + "and had none left for the actual response.\n\n"
                        + "To fix this:\n"
                        + "→ Try increasing max_tokens\n"
                        + "→ Or simplify the request";
                    log.warn("Reasoning exhausted the output token budget — no visible response was produced.");
                    turnMessages.add(Message.assistant(exhaustMsg, turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, true, TurnExitReason.THINKING_BUDGET_EXHAUSTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }

                // ── Part E: Empty response recovery (parity with Hermes conversation_loop.py:4170-4197) ──
                // Mirrors Hermes _empty_content_retries (max 3):
                // When the model returns a truly empty response (no content, no tool calls,
                // no thinking blocks), retry up to 3 times with nudges before treating as terminal.
                //   Attempt 1: add a "nudge" user message asking the model to respond
                //   Attempt 2: add a prefill assistant message ("Let me continue...")
                //   Attempt 3: treat as terminal — return EMPTY_RESPONSE_EXHAUSTED exit reason
                String visibleContent = stripThinkBlocksFromString(response.content());
                if (visibleContent != null) {
                    visibleContent = visibleContent.strip();
                }
                String rawContent = response.content() != null ? response.content() : "";
                boolean isTrulyEmpty = (visibleContent == null || visibleContent.isBlank())
                    && !containsAnyThinkTag(rawContent);
                boolean prefillExhausted = thinkingPrefillRetries >= 2;
                if (isTrulyEmpty && (!containsAnyThinkTag(rawContent) || prefillExhausted)) {
                    // h63: When the model returns a deterministic empty response (not an error, just empty),
                    // don't retry it — return immediately with a message, unless config overrides.
                    if (!properties.getCore().isEmptyResponseRetry()) {
                        log.warn("Empty response (no content) — returning immediately (agent.empty-response.retry=false)");
                        turnMessages.add(Message.assistant("(No response generated)", turnIndex));
                        if (turnFinalizer != null) {
                            turnFinalizer.finalize(session.id(), turnMessages, true, TurnExitReason.EMPTY_RESPONSE);
                        }
                        return new TurnResult(turnMessages, true, null);
                    }
                    // ── Empty-response recovery (Hermes conversation_loop.py:7640-7712) ──
                    // Budget 3 SEPARATE from thinking-prefill; jittered backoff base 5s/cap 60s
                    // (interruptible) BEFORE each retry — never instant retries; post-tool rounds
                    // get _EMPTY_TOOL_RESPONSE_NUDGE with a synthetic "(empty)" assistant stub for
                    // role alternation; plain-empty rounds get NO synthetic messages at all.
                    // R3: nudges/stubs live in a LOCAL retry list — they are never appended to
                    // turnMessages and thus never persisted (Hermes strips this scaffolding before
                    // persistence; we keep it out of the durable list entirely).
                    // P4 parity: pass the provider-reported usage so deterministic-empty
                    // detection (≥2 consecutive zero-output attempts) can actually fire.
                    // Hermes empty_response_guard.py:172 — cost-aware early stop.
                    emptyGuard.recordEmptyAttempt(
                        properties.getModel().getModelName(),
                        properties.getModel().getProvider(),
                        response.finishReason(),
                        response.usage() != null ? (long) response.usage().completionTokens() : null);
                    boolean deterministicEmpty = emptyGuard.deterministicEmpty();
                    if (deterministicEmpty) {
                        log.warn("Deterministic empty response detected (consecutive zero-output, "
                            + "model={} provider={}) — skipping remaining retries to avoid repeat charges",
                            properties.getModel().getModelName(), properties.getModel().getProvider());
                    }
                    if (!deterministicEmpty
                            && retryStateEmptyResponse < ResponseRecoveryPolicy.MAX_EMPTY_RESPONSE_ATTEMPTS) {
                        retryStateEmptyResponse++;
                        log.warn("Empty response (no content or reasoning) — retry {}/{}",
                            retryStateEmptyResponse, ResponseRecoveryPolicy.MAX_EMPTY_RESPONSE_ATTEMPTS);
                        long backoffMs = ResponseRecoveryPolicy.jitteredBackoffMs(retryStateEmptyResponse,
                            properties.getCore().getEmptyBackoffBaseMs(),
                            properties.getCore().getEmptyBackoffCapMs());
                        try {
                            com.azhukov.agent.core.agent.TurnExecutorUtils.interruptibleSleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.info("Empty-response backoff interrupted — aborting turn");
                            break;
                        }
                        // P-06: bypass OpenRouter response caching for this retry
                        com.azhukov.agent.client.langchain4j.EmptyRetryCacheBypass.markEmptyRetry();
                        List<Message> retryContext = new ArrayList<>(turnMessages);
                        if (lastResponseHadToolCalls) {
                            // Hermes 7568-7577: tool → user alternation needs the synthetic
                            // "(empty)" stub; strict providers reject tool→user sequences.
                            retryContext.add(Message.assistant("(empty)", turnIndex));
                            retryContext.add(Message.user(ResponseRecoveryPolicy.EMPTY_AFTER_TOOLS_NUDGE));
                        }
                        // Plain-empty: Hermes adds NO synthetic message — backoff + fresh call only.
                        turnIndex++;
                        context = contextEngine.prepareContext(session, retryContext);
                        session = resolveRotatedSession(session);
                        continue;
                    }
                    // ── Exhausted (Hermes 7728-7760): fallback attempt BEFORE terminal ──
                    TurnModelState modelState = turnModelState.get();
                    if (modelState != null && modelState.fallbackManager.hasPendingFallback()) {
                        log.warn("Empty response after {} retries — attempting fallback provider",
                            retryStateEmptyResponse);
                        FallbackModelCaller.ModelCallContext fmc = new FallbackModelCaller.ModelCallContext(
                            modelClient, modelState.fallbackManager);
                        fmc.activeClient = modelState.activeClient;
                        if (fallbackModelCaller().tryActivateFallbackForEmpty(fmc)) {
                            modelState.activeClient = fmc.activeClient;
                            retryStateEmptyResponse = 0; // Hermes: reset budget for the fallback model
                            emptyGuard.reset();           // ...and the deterministic-streak tracker
                            continue;
                        }
                    }
                    // Terminal: "(empty)" sentinel is USER-FACING ONLY — never persisted
                    // (Hermes _empty_terminal_sentinel + _drop_trailing_empty_response_scaffolding).
                    log.error("Empty response after {} retries — returning terminal EMPTY_RESPONSE_EXHAUSTED",
                        ResponseRecoveryPolicy.MAX_EMPTY_RESPONSE_ATTEMPTS);
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.EMPTY_RESPONSE_EXHAUSTED);
                    }
                    return new TurnResult(turnMessages, true, "(empty)");
                }
                lastResponseHadToolCalls = false; // clean text round — plain backoff next time
                turnMessages.add(Message.assistant(visibleContent, turnIndex));
                // P1-5: Persist the final assistant message immediately
                if (midTurnPersistenceCallback != null) {
                    // M6: Only advance cursor if persistence succeeded
                    if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                        persistedUpTo = turnMessages.size();
                    }
                }
                log.debug("Turn {} completed without tool calls", i);
                // Nudge-gated background review — only fire when counters hit thresholds.
                // Mirrors Hermes: memory review every N user turns, skill review every M tool iterations.
                boolean interrupted = interruptToken != null && interruptToken.isCancelled(session.id());
                triggerNudgedBackgroundReview(session, turnMessages, interrupted);

                // ── Verify-on-stop guard (Hermes parity: verification_stop.py) ──
                // When the model finishes (STOP) after editing code without fresh
                // verification evidence, inject a nudge requesting tests/build.
                if (properties.getVerifyOnStop().isEnabled() && toolExecutionService != null
                    && toolExecutionService.getFileMutationTracker() != null) {
                    var tracker = toolExecutionService.getFileMutationTracker();
                    var changedPaths = tracker.getTurnMutationPaths();
                    if (!changedPaths.isEmpty()
                        && tracker.getVerificationStopNudges() < verifyOnStopGuard.getMaxNudgeAttempts()) {
                        List<String> verifyCommands = codingWorkspaceSnapshot != null
                            ? codingWorkspaceSnapshot.getVerifyCommands() : List.of();
                        String nudge = verifyOnStopGuard.buildNudge(
                            changedPaths, tracker.getVerificationStopNudges(), verifyCommands);
                        if (nudge != null) {
                            tracker.incrementVerificationStopNudges();
                            log.info("Verify-on-stop nudge issued for session {} (attempt {}, {} changed paths)",
                                session.id(), tracker.getVerificationStopNudges(), changedPaths.size());
                            // assistant content already recorded above — do not duplicate (PR-3 fix)
                            turnMessages.add(Message.user(nudge));
                            // Continue the loop — model gets another turn to verify
                            continue;
                        }
                    }
                }

                TurnExitReason reason = (visibleContent == null || visibleContent.isBlank())
                    ? TurnExitReason.EMPTY_RESPONSE : TurnExitReason.COMPLETED;
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, true, reason);
                }
                return new TurnResult(turnMessages, true, null);
            }

            // ── Tool call path: reset prefill counter on successful tool calls ──
            // Mirrors Hermes: reset _thinking_prefill_retries when tool calls follow
            // a prefill recovery (conversation_loop.py:3885)
            thinkingPrefillRetries = 0;

            // ── Commentary emission (parity with Hermes _emit_interim_assistant_message) ──
            // When the LLM returns BOTH text AND tool calls, the text is "commentary" —
            // an interim assistant message shown to the user before tool execution.
            // In the non-streaming path, the text was NOT already shown, so
            // alreadyStreamed=false — the callback should send it as a new message.
            // M21 fix: scrub think blocks from commentary before it reaches the user
            // (the streaming path already strips them at emission).
            if (properties.isCommentaryEnabled() && response.hasContent() && response.hasToolCalls()) {
                String commentary = stripThinkBlocksFromString(response.content());
                if (commentary != null && !commentary.isBlank() && commentaryCallback != null) {
                    try {
                        commentaryCallback.onCommentary(session.id(), commentary.strip(), false);
                    } catch (Exception e) {
                        log.warn("Commentary callback failed for session {}: {}", session.id(), e.getMessage());
                    }
                    log.debug("Emitted commentary for session {} (alreadyStreamed=false): {} chars",
                        session.id(), commentary.length());
                }
            }

            // ── Tool call validation pipeline (parity with Hermes conversation_loop.py) ──
            // P-02: single shared owner of the validation order (uniquify →
            // name repair → JSON validation → delegate cap/dedupe) used by BOTH
            // the sync runtime and the SSE streaming path.
            List<ToolCall> toolCalls;
            if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                List<ToolCall> uniquified = new ArrayList<>(response.toolCalls());
                ToolCallValidator.uniquifyToolCallIds(uniquified);
                toolCalls = uniquified;
            } else {
                toolCalls = response.toolCalls();
            }

            // Preserve commentary text in the assistant message alongside tool calls
            // (built from the UNIQUIFIED calls so persistence matches execution)
            if (response.hasContent() && response.hasToolCalls()) {
                turnMessages.add(Message.assistantWithToolCalls(response.content(), toolCalls, turnIndex));
            } else if (response.hasToolCalls()) {
                turnMessages.add(Message.assistantToolCalls(toolCalls, turnIndex));
            }

            // P1-5: Persist the assistant message (with tool calls) immediately.
            // Mirrors Hermes _persist_session after appending assistant_msg.
            boolean assistantPersisted = true;
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                } else {
                    assistantPersisted = false;
                }
            }

            // P6 parity (conversation_loop.py:7437-7449): when the canonical append
            // fails, do NOT run side-effecting tools from state that exists only
            // in this process. Abort the turn with the persistence-failed exit
            // reason; retrying would burn the iteration budget on unpersisted state.
            if (!assistantPersisted) {
                log.error("Assistant tool-call persistence failed before execution (session={}) — " +
                    "aborting turn without executing tools", session.id());
                return new TurnResult(turnMessages, false,
                    "Session persistence failed — tool execution skipped to avoid side effects on unpersisted state.");
            }

            int currentTurnIndex = turnIndex;

            // c2: single shared pre-execution pipeline (P-02) + canonical batch
            // executor. The sync loop previously ran this validation/approval/
            // dispatch sequence inline — a verbatim sibling of the streaming
            // loop's copy — so fixes drifted between the two surfaces.
            Set<String> registeredToolNames = new HashSet<>();
            for (ToolDefinition td : tools) {
                registeredToolNames.add(td.name());
            }
            ToolBatchPipeline.PipelineResult pipeline =
                toolBatchPipeline().prepare(toolCalls, registeredToolNames, currentTurnIndex);
            if (pipeline.truncatedArgs()) {
                log.warn("Truncated tool call arguments detected — refusing to execute.");
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
                }
                return TurnResult.error("Response truncated due to output length limit");
            }
            if (!pipeline.syntheticResults().isEmpty()) {
                turnMessages.addAll(pipeline.syntheticResults());
            }
            if (pipeline.executableCalls().isEmpty()) {
                turnIndex++;
                continue;
            }
            toolCalls = pipeline.executableCalls();

            // Hermes /yolo parity + subagent auto-approve: skip the approval
            // gate when explicitly requested for this session/request.
            boolean skipApproval = "true".equals(session.getMetadata("subagent_auto_approve"))
                || Boolean.TRUE.equals(options != null ? options.yoloMode() : null)
                || "true".equals(session.getMetadata("yoloMode"));

            // L6/C4: reset skill/memory nudge counters BEFORE execution (Hermes
            // resets _iters_since_skill before the tool runs). The sync runtime
            // keeps its own hydrated counter maps, so the reset stays here
            // rather than in TurnExecutor's MemoryNudgeManager hook.
            for (ToolCall call : toolCalls) {
                if ("skill_manage".equals(call.name())) {
                    itersSinceSkill.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                }
                if ("memory".equals(call.name())) {
                    turnsSinceMemory.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                }
            }

            TurnExecutor.ToolBatchResult batchResult = turnExecutor().executeToolBatch(
                toolCalls, registeredToolNames, session, turnState, currentTurnIndex,
                skipApproval, null);
            // Post-batch budget accounting (incl. the execute_code refund,
            // previously streaming-only — Hermes conversation_loop.py:7277-7280).
            for (TurnExecutor.ToolExecutionRecord rec : batchResult.executions()) {
                budget = iterationBudget.recordToolExecution(budget, rec.toolName(), rec.durationMs());
                if (rec.refunded()) {
                    budget = iterationBudget.refundToolExecution(budget);
                }
            }
            if (batchResult.isInterrupted()) {
                turnMessages.addAll(batchResult.toolResults());
                turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                }
                return new TurnResult(turnMessages, true, null);
            }
            List<Message> toolResults = batchResult.toolResults();

            // c2: post-batch steer injection now lives inside
            // TurnExecutor.executeToolBatch (enforce-then-inject order), so the
            // turn results returned here already carry any pending steer note.
            turnMessages.addAll(toolResults);
            // Hermes empty-recovery driver: the previous round landed tool calls.
            lastResponseHadToolCalls = true;

            // P1-5: Persist tool result messages immediately after the batch completes.
            // Mirrors Hermes _persist_session after _execute_tool_calls.
            // If the JVM crashes after tool execution but before the next model call,
            // all tool results are preserved in the database.
            boolean toolResultsPersisted = true;
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                } else {
                    toolResultsPersisted = false;
                }
            }

            // P6 parity (conversation_loop.py:7474-7478): a tool result that cannot be
            // made canonical must not be sent back to the model — abort the turn
            // instead of continuing from in-memory-only state.
            if (!toolResultsPersisted) {
                log.error("Tool-result persistence failed (session={}) — aborting turn", session.id());
                return new TurnResult(turnMessages, false,
                    "Session persistence failed after tool execution — turn aborted.");
            }

            turnIndex++;

            // ── Proactive compression (post-tool-batch) ──
            // Mirrors Hermes conversation_loop.py:3960-3998: after each tool batch,
            // before the next model call, check if compression should be triggered.
            // This is IN ADDITION to the existing reactive compression on CONTEXT_OVERFLOW.
            if (contextCompressor instanceof DefaultContextCompressor dcc
                && properties.getCompression().isEnabled()) {
                // Estimate tokens from the current turn messages
                int estimatedTokens = tokenEstimator.estimateTokens(turnMessages);
                // Get context window size from the engine (or fall back to config)
                int contextWindowSize = 0;
                if (contextEngine instanceof DefaultContextEngine dce) {
                    // Use reflection-safe access: contextLength is package-private
                    contextWindowSize = dce.getContextLength();
                }
                if (contextWindowSize <= 0) {
                    contextWindowSize = properties.getContext().getMaxTokens();
                }
                if (dcc.shouldCompressProactive(estimatedTokens, contextWindowSize)) {
                    log.info("  ⟳ Proactive compression triggered after tool batch (estimated {} tokens, threshold at 50% of {})",
                        estimatedTokens, contextWindowSize);
                    int targetChars = properties.getContext().getTargetTokens() * 4;
                    List<Message> compressed = dcc.compress(turnMessages, targetChars);
                    if (compressed.size() < turnMessages.size()
                        || compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                           < turnMessages.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()) {
                        turnMessages.clear();
                        turnMessages.addAll(compressed);
                        log.info("Proactive compression: {} -> {} messages",
                            compressed.size(), turnMessages.size());
                    }
                }
            }
          } catch (Exception outerEx) {
            // P-08 (Hermes #92450): every escaped exception counts against the
            // per-turn budget; at the cap the turn terminates instead of spinning.
            if (outerErrors.recordAndCheckExhausted()) {
                log.error("Outer error budget exhausted ({}/{}) — terminating turn: {}",
                    outerErrors.count(), outerErrors.cap(), outerEx.getMessage(), outerEx);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
                }
                return TurnResult.error(outerErrors.exhaustedMessage(outerEx.getMessage()));
            }
            log.warn("Outer loop error {}/{} — continuing turn: {}",
                outerErrors.count(), outerErrors.cap(), outerEx.getMessage());
          }
        }

        // H7: Fire background review on max-turns-reached path too.
        boolean interrupted = interruptToken != null && interruptToken.isCancelled(session.id());
        triggerNudgedBackgroundReview(session, turnMessages, interrupted);

        if (turnFinalizer != null) {
            turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
        }
        return TurnResult.error("Reached max turns without completion");
    }

    /**
     * Calls modelClient.complete() with retry logic based on ErrorClassifier.
     * <p>
     * Retry strategy (parity with Hermes conversation_loop.py):
     * <ul>
     * <li>RATE_LIMIT: parse Retry-After header (capped 120s), fall back to exponential backoff</li>
     * <li>OVERLOADED: exponential backoff (2s * 2^attempt, cap 60s)</li>
     * <li>RETRYABLE/TIMEOUT/SERVER_ERROR: jittered exponential (500ms * 2^attempt + 0-250ms, cap 60s)</li>
     * <li>CONTEXT_OVERFLOW: trigger compression, then retry with compressed context</li>
     * <li>CONTENT_POLICY: terminal — don't retry, return user-friendly message (Part F)</li>
     * <li>PERMANENT/BILLING/AUTH_PERMANENT/MODEL_NOT_FOUND: terminal — fail immediately</li>
     * </ul>
     * One-shot recovery guards (parity with Hermes TurnRetryState — 14 total):
     * <ul>
     * <li>authRetryAttempted — generic auth refresh</li>
     * <li>codexAuthRetryAttempted — Codex OAuth refresh</li>
     * <li>anthropicAuthRetryAttempted — Anthropic OAuth refresh</li>
     * <li>copilotAuthRetryAttempted — Copilot auth refresh</li>
     * <li>thinkingSigRetryAttempted — strip thinking blocks</li>
     * <li>imageShrinkRetryAttempted — strip image content</li>
     * <li>multimodalToolContentRetryAttempted — strip multimodal tool content</li>
     * <li>invalidEncryptedContentRetryAttempted — strip codex reasoning replay</li>
     * <li>oauth1mBetaRetryAttempted — disable 1M context beta</li>
     * <li>llamaCppGrammarRetryAttempted — strip tool schema patterns</li>
     * <li>hasRetried429 — track 429 for credential rotation</li>
     * <li>compressionRestartAttempted — restart with compressed messages</li>
     * <li>lengthContinuationAttempted — retry with length-continuation prompt</li>
     * </ul>
     * <p>
     * Backoff is interruptible — sleeps in 200ms increments checking for interrupts (Part D).
     */
    /**
     * c1: model-call retry + fallback delegated to {@link FallbackModelCaller}
     * (extracted from the former callModelWithRetry/tryActivateFallback — ~420 LOC).
     * The per-turn {@link FallbackModelCaller.ModelCallContext} carries the
     * active/fallback client state this runtime owns.
     */
    private ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools, Session session,
                                             ModelRequestOptions options) {
        TurnModelState modelState = turnModelState.get();
        FallbackModelCaller.ModelCallContext callCtx = new FallbackModelCaller.ModelCallContext(
            modelClient, modelState != null ? modelState.fallbackManager : null);
        // Adopt a previously-activated fallback client from an earlier turn iteration.
        callCtx.activeClient = modelState != null ? modelState.activeClient : null;
        ChatResponse response = fallbackModelCaller().call(callCtx, context, tools, session, options);
        // Persist a fallback activated during this call for the rest of the turn.
        if (modelState != null) {
            modelState.activeClient = callCtx.activeClient;
        }
        return response;
    }


    /**
     * Sleep for the given delay in 200ms increments, checking for thread interrupts
     * between each chunk. Mirrors Hermes conversation_loop.py:1324-1347 backoff sleep.
     * <p>
     * This allows the agent to respond to interrupts (user cancellation, session teardown)
     * promptly instead of blocking for the full backoff duration.
     *
     * @param delayMs total sleep time in milliseconds
     * @throws InterruptedException if the thread was interrupted during sleep
     */
    static void interruptibleSleep(long delayMs) throws InterruptedException {
        // M3 fix: delegate to the single corrected implementation in TurnExecutorUtils.
        TurnExecutorUtils.interruptibleSleep(delayMs);
    }

    // c2: message/tool recovery helpers (extractRetryAfterMs, lowerMessageContains,
    // stripGrammarPatternsFromTools, detectRefusalPattern, thinking/image/multimodal
    // contains+strip pairs) moved to TurnExecutor as public statics — dead local
    // copies removed after FallbackModelCaller extraction.
    // c2: requestBudgetExhaustionSummary moved to TurnExecutor (shared owner —
    // the streaming path previously had no summary call at all).

    /**
     * Nudge-gated background review trigger.
     * Mirrors Hermes _turns_since_memory / _iters_since_skill:
     * - Memory review fires when turnsSinceMemory >= memoryNudgeInterval (default 10)
     * - Skill review fires when itersSinceSkill >= skillCreationNudgeInterval (default 10)
     * - Skipped entirely if the turn was interrupted
     * - Only fires if at least one nudge threshold is met
     * - C3: Passes the parent session's userId to the review so memory writes
     *   are attributed to the actual user.
     * - C5: Passes the full conversation history (including prior session messages
     *   loaded by the context engine) to the review, not just the current turn.
     */
    private void triggerNudgedBackgroundReview(Session session, List<Message> turnMessages, boolean interrupted) {
        if (interrupted) {
            return;
        }
        if (backgroundReviewService == null) {
            return;
        }
        // h64: Skip the automatic background review when running inside a delegation subagent.
        // delegationDepth > 0 means we're in a child subagent, so don't trigger review.
        String delegationDepthMeta = session.getMetadata("delegation_depth");
        if (delegationDepthMeta != null) {
            try {
                int delegationDepth = Integer.parseInt(delegationDepthMeta.trim());
                if (delegationDepth > 0) {
                    log.debug("Skipping background review for subagent (delegationDepth={})", delegationDepth);
                    return;
                }
            } catch (NumberFormatException e) {
                // Ignore — treat as depth 0
            }
        }

        // Hermes parity (turn_finalizer.py:794, cron/scheduler.py:5459): cron/background
        // sessions set skip_background_review — review forks cost ~30K tokens and cron
        // has no human-in-the-loop benefit from a memory/skill review.
        if ("true".equalsIgnoreCase(session.getMetadata("skip_background_review"))) {
            log.debug("Skipping background review for background session (skip_background_review)");
            return;
        }

        int memNudge = properties.getMemory().getNudgeInterval();
        int skillNudge = properties.getSkills().getCreationNudgeInterval();

        boolean shouldReviewMemory = false;
        boolean shouldReviewSkills = false;

        if (memNudge > 0) {
            AtomicInteger turnsCounter = turnsSinceMemory.get(session.id());
            if (turnsCounter != null && turnsCounter.get() >= memNudge) {
                shouldReviewMemory = true;
                turnsCounter.set(0);
            }
        }

        if (skillNudge > 0) {
            AtomicInteger itersCounter = itersSinceSkill.get(session.id());
            if (itersCounter != null && itersCounter.get() >= skillNudge) {
                shouldReviewSkills = true;
                itersCounter.set(0);
            }
        }

        if (!shouldReviewMemory && !shouldReviewSkills) {
            return;
        }

        // C5: Build full conversation history (prior session messages + current turn)
        // so the background review sees the complete context, not just the current turn.
        List<Message> fullHistory;
        try {
            fullHistory = contextEngine.prepareContext(session, turnMessages);
        } catch (Exception e) {
            log.warn("Failed to prepare full context for background review, using turn messages: {}", e.getMessage());
            fullHistory = turnMessages;
        }

        try {
            backgroundReviewService.clearFlag(session.id());
            // C3: Pass the parent session's userId so memory writes go to the actual user.
            backgroundReviewService.reviewTurn(session.id(), fullHistory, session.userId(),
                shouldReviewMemory, shouldReviewSkills);
        } catch (Exception e) {
            log.warn("Background review trigger failed: {}", e.getMessage());
        }

        // H9: Surface the review summary if one was produced by a prior review.
        // The async review may not have completed yet, so this logs any pending
        // summary from a previous turn's review. The current turn's review will
        // be surfaced on the next turn.
        try {
            String summary = getReviewSummaryForSurface(session.id());
            if (summary != null && !summary.isBlank()) {
                log.info("Background review summary for session {}: {}", session.id(), summary);
            }
        } catch (Exception e) {
            log.debug("No review summary to surface for session {}", session.id());
        }
    }

    /**
     * S3: Check if the background review produced a summary to surface to the user.
     * Called by the turn finalizer or session-end hook.
     */
    public String getReviewSummaryForSurface(UUID sessionId) {
        if (backgroundReviewService == null) {
            return null;
        }
        if (!backgroundReviewService.hasReviewSummary(sessionId)) {
            return null;
        }
        ReviewSummary summary = backgroundReviewService.getReviewSummary(sessionId);
        if (summary == null || !summary.hasActions()) {
            return null;
        }
        // Return the formatted summary and clear it so it's only surfaced once
        String result = summary.formattedSummary();
        backgroundReviewService.clearFlag(sessionId);
        return result;
    }

    // c2: executeToolsInParallel removed — the canonical parallel dispatch
    // (M17 shared executor + P5 interruptible poll) lives in
    // TurnExecutor.executeToolsInParallel, called via executeToolBatch.

    private int estimateResponseTokens(ChatResponse response) {
        int chars = response.content() != null ? response.content().length() : 0;
        if (response.toolCalls() != null) {
            for (ToolCall tc : response.toolCalls()) {
                chars += tc.arguments() != null ? tc.arguments().length() : 0;
                chars += tc.name() != null ? tc.name().length() : 0;
            }
        }
        return chars / 4 + 1;
    }

    /**
     * WARNING 1: Clean up per-session state maps to prevent memory leaks.
     * <p>
     * Removes entries from {@code sessionLocks}, {@code turnsSinceMemory}, and
     * {@code itersSinceSkill} for the given session. This should be called when a
     * session is deleted, rotated, or otherwise no longer needs runtime tracking.
     * <p>
     * If no session-deletion/rotation hook exists yet, callers should wire this into
     * their session lifecycle management (e.g. TurnFinalizer or session deletion endpoints).
     *
     * @param sessionId the UUID of the session to clean up
     */
    public void cleanupSession(UUID sessionId) {
        if (sessionId == null) return;
        sessionLocks.remove(sessionId);
        turnsSinceMemory.remove(sessionId);
        itersSinceSkill.remove(sessionId);
        // Evict per-session state held by collaborating components so that
        // deleting a session cannot leak memory across the runtime.
        turnStateManager.clear(sessionId);
        interruptToken.remove(sessionId);
        contextEngine.evict(sessionId);
        backgroundReviewService.clearFlag(sessionId);
        if (steerBuffer != null) {
            steerBuffer.clear(sessionId);
        }
        // rev-63: guardrail per-session state (history, halted, consecutive
        // failures) was never removed — unbounded leak on long-running service.
        if (toolGuardrails != null) {
            toolGuardrails.removeSession(sessionId);
        }
        log.debug("Cleaned up runtime state maps for session {}", sessionId);
    }

    /**
     * Reacts to {@link SessionDeletedEvent} (published by session deletion/rotation
     * endpoints) and evicts all per-session in-memory state. Without this, the
     * per-session ConcurrentHashMap entries in this runtime and its collaborators
     * accumulate forever — an unbounded memory leak (audit finding C3).
     */
    @org.springframework.context.event.EventListener
    public void onSessionDeleted(SessionDeletedEvent event) {
        cleanupSession(event.sessionId());
    }

    /** c2: session-rotation resolution for recovery continuations (same as streaming). */
    private Session resolveRotatedSession(Session session) {
        if (contextEngine instanceof com.azhukov.agent.core.context.DefaultContextEngine dce) {
            java.util.Optional<Session> rotated = dce.resolveRotatedSession(session);
            if (rotated.isPresent()) {
                Session ns = rotated.get();
                log.info("Switching to rotated session: old={}, new={}", session.id(), ns.id());
                return ns;
            }
        }
        return session;
    }
}