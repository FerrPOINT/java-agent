package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.credential.CredentialPool;
import com.azhukov.agent.client.credential.PooledCredential;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
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

    // Fallback manager — created per-turn, manages mid-turn model switching.
    // Mirrors Hermes _fallback_chain / _fallback_index / _fallback_activated.
    private FallbackManager fallbackManager;

    /** c1: extracted retry+fallback loop owner (lazy — plain fields, no ctor churn). */
    private volatile FallbackModelCaller fallbackModelCaller;
    private final ReentrantLock turnLock = new ReentrantLock();

    private FallbackModelCaller fallbackModelCaller() {
        FallbackModelCaller fmc = fallbackModelCaller;
        if (fmc == null) {
            turnLock.lock();
            try {
                if (fallbackModelCaller == null) {
                    fallbackModelCaller = new FallbackModelCaller(
                        errorClassifier, properties, contextCompressor, contextEngine);
                }
                fmc = fallbackModelCaller;
            } finally {
                turnLock.unlock();
            }
        }
        return fmc;
    }

    // Active model client — may be swapped to a fallback client mid-turn.
    private ModelClient activeModelClient;

    // Shared daemon executor for memory sync — avoids creating a new executor every turn
    // Virtual threads are daemon by default in Java 25
    private final ExecutorService memorySyncExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("memory-sync-", 0).factory());

    // M17: Shared executor for parallel tool execution — avoids creating a new executor per batch
    private final ExecutorService parallelToolExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("tool-parallel-", 0).factory());

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
        parallelToolExecutor.shutdown();
        try {
            if (!parallelToolExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                parallelToolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            parallelToolExecutor.shutdownNow();
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
        List<Message> sanitized = messageSanitizer.sanitize(messages);
        List<Message> context = contextEngine.prepareContext(
            Session.create("openai-user", "openai-compatible", ""), sanitized);
        ModelClient client = activeModelClient != null ? activeModelClient : modelClient;
        return client.complete(context, tools);
    }

    @Override
    public TurnResult runTurn(Session session, String userInput, List<String> references,
                              ModelRequestOptions options) {
        ModelRequestOptions effectiveOptions = options != null ? options : ModelRequestOptions.empty();
        // Acquire per-session lock to prevent concurrent turns on the same session.
        // Audit H1: use tryLock with a bounded wait instead of blocking indefinitely.
        // The previous lock.lock() would block for up to 5 minutes while an
        // approval gate was being awaited inside runTurnInternal, effectively
        // hanging the session if a second request arrived during that window.
        UUID sid = session.id();
        java.util.concurrent.locks.ReentrantLock lock = sessionLocks.computeIfAbsent(sid, k -> new java.util.concurrent.locks.ReentrantLock());
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
        }
    }

    private TurnResult runTurnInternal(Session session, String userInput, List<String> references,
                                       ModelRequestOptions options) {
        UUID sessionIdUuid = session.id();
        String sessionId = sessionIdUuid.toString();
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
        if (fallbackManager != null) {
            fallbackManager.restorePrimary();
        }
        fallbackManager = new FallbackManager(
            properties.getFallbackChain(),
            properties.getModel().getProvider(),
            properties.getModel().getModelName(),
            properties.getModel().getBaseUrl(),
            properties.getModel().getApiKey()
        );
        activeModelClient = modelClient;

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
        if (systemPromptOverride != null && !systemPromptOverride.isBlank()
                && promptBuilder instanceof DefaultPromptBuilder dpb) {
            turnMessages.add(dpb.buildSystemMessage(session, systemPromptOverride));
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
        try {
        result = runTurnLoop(session, turnMessages, tools, maxTurns, turnIndex, budget, turnState, sessionId, sessionIdUuid, options, effectiveToolsets);
        } finally {
            // Clean up per-session guardrail state to prevent memory leaks (REM-2)
            guardrail.reset(sessionIdUuid);
            // Clear any pending steer that wasn't consumed (e.g. turn was
            // interrupted before the next tool batch could drain it).
            if (steerBuffer != null) {
                steerBuffer.clear(sessionIdUuid);
            }
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
        if (fallbackManager != null && fallbackManager.isFallbackActivated()) {
            fallbackManager.restorePrimary();
            activeModelClient = modelClient;
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
        boolean runBudgetWrapupInjected = false;
        // Hermes: whether the PREVIOUS model round landed tool calls — drives the
        // empty-recovery nudge choice (post-tool stub vs plain backoff).
        boolean lastResponseHadToolCalls = false;
        // R4 (Hermes empty_response_guard): deterministic-empty detection —
        // ≥2 consecutive zero-output attempts with identical signature skip retries.
        EmptyResponseGuard emptyGuard = new EmptyResponseGuard();

        // P1-5: Mid-turn persistence cursor — tracks how many messages have been
        // flushed to the database. After each tool batch, new messages (assistant
        // with tool calls + tool results) are persisted immediately, mirroring
        // Hermes' _persist_session / _flush_messages_to_session_db pattern.
        // If the JVM crashes mid-turn, all progress up to the last batch is preserved.
        int persistedUpTo = turnMessages.size();

        for (int i = 0; i < maxTurns; i++) {
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
                // printing a raw "budget exhausted" message.
                String summary = requestBudgetExhaustionSummary(session, turnMessages, options);
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
            // Hermes parity: pre-API-call /steer drain (conversation_loop.py:2104-2153).
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
                            log.info("Pre-API steer drain (sync): injected into tool msg at index {}", si);
                            break;
                        }
                    }
                    if (!injected) {
                        steerBuffer.steer(session.id(), preApiSteer);
                    }
                }
            }
            // Hermes parity: wall-clock run-budget wrap-up notice (conversation_loop.py:2154-2172).
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
                            log.info("Run budget wrap-up notice injected (sync) (budget={}s, elapsed={}s)",
                                runBudget, elapsed);
                            break;
                        }
                    }
                }
            }
            ChatResponse response;
            try {
                long callStart = System.currentTimeMillis();
                response = callModelWithRetry(context, tools, session, options);
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
                ModelRequestOptions boostedOptions = new ModelRequestOptions(
                    options.modelName(), options.reasoningEffort(),
                    options.fastMode(), options.voiceMode(),
                    options.personality(), options.subgoal(),
                    boostedMax);
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
                // Close the interrupted tool sequence with recovery stubs
                for (ToolCall tc : response.toolCalls()) {
                    turnMessages.add(Message.assistantWithToolCalls(response.content(), List.of(tc), turnIndex));
                    turnMessages.add(Message.toolResult(tc.id(),
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
                truncatedParts.append(partialContent);
                List<Message> lengthContext = new ArrayList<>(turnMessages);
                lengthContext.add(Message.assistant(partialContent, turnIndex));
                lengthContext.add(Message.user(ResponseRecoveryPolicy.LENGTH_NUDGE));
                turnIndex++;
                context = contextEngine.prepareContext(session, lengthContext);
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
                    emptyGuard.recordEmptyAttempt(
                        properties.getModel().getModelName(),
                        properties.getModel().getProvider(),
                        response.finishReason(), null /* usage not on ChatResponse yet — fail-open */);
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
                    if (fallbackManager != null && fallbackManager.hasPendingFallback()) {
                        log.warn("Empty response after {} retries — attempting fallback provider",
                            retryStateEmptyResponse);
                        FallbackModelCaller.ModelCallContext fmc = new FallbackModelCaller.ModelCallContext(
                            modelClient, fallbackManager);
                        fmc.activeClient = activeModelClient;
                        if (fallbackModelCaller().tryActivateFallbackForEmpty(fmc)) {
                            activeModelClient = fmc.activeClient;
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
                            turnMessages.add(Message.assistant(visibleContent, turnIndex));
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
            if (properties.isCommentaryEnabled() && response.hasContent() && response.hasToolCalls()) {
                if (commentaryCallback != null) {
                    try {
                        commentaryCallback.onCommentary(session.id(), response.content(), false);
                    } catch (Exception e) {
                        log.warn("Commentary callback failed for session {}: {}", session.id(), e.getMessage());
                    }
                }
                log.debug("Emitted commentary for session {} (alreadyStreamed=false): {} chars",
                    session.id(), response.content().length());
            }

            // Preserve commentary text in the assistant message alongside tool calls
            if (response.hasContent() && response.hasToolCalls()) {
                turnMessages.add(Message.assistantWithToolCalls(response.content(), response.toolCalls(), turnIndex));
            } else {
                turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));
            }

            // P1-5: Persist the assistant message (with tool calls) immediately.
            // Mirrors Hermes _persist_session after appending assistant_msg.
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                }
            }

            int currentTurnIndex = turnIndex;
            List<ToolCall> toolCalls = response.toolCalls();

            // ── Tool call validation pipeline (parity with Hermes conversation_loop.py) ──
            // 0. Uniquify duplicate tool-call ids BEFORE any downstream consumer
            //    (Hermes conversation_loop.py:6827 — models reusing one id in a batch
            //    lose the later call's result; strict providers reject duplicates).
            if (toolCalls != null) {
                toolCalls = new ArrayList<>(toolCalls);
                ToolCallValidator.uniquifyToolCallIds(toolCalls);
            }

            // 1. Validate tool names — repair fuzzy mismatches, collect errors
            Set<String> registeredToolNames = new HashSet<>();
            for (ToolDefinition td : tools) {
                registeredToolNames.add(td.name());
            }
            List<String> nameErrors = ToolCallValidator.validateToolNames(toolCalls, registeredToolNames);
            if (!nameErrors.isEmpty()) {
                log.warn("Invalid tool calls detected: {}", nameErrors);
                // h53: When the LLM returns a batch of tool calls where some have valid names
                // and some have invalid (non-existent) names, execute the valid ones and return
                // errors for the invalid ones, instead of failing the entire batch.
                List<ToolCall> validCalls = new ArrayList<>();
                List<Message> errorResults = new ArrayList<>();
                for (ToolCall tc : toolCalls) {
                    if (registeredToolNames.contains(tc.name())) {
                        validCalls.add(tc);
                    } else {
                        // Invalid tool name — return error for this specific call
                        errorResults.add(Message.toolResult(tc.id(),
                            "Tool '" + tc.name() + "' does not exist. Available tools: "
                            + String.join(", ", new java.util.TreeSet<>(registeredToolNames)),
                            currentTurnIndex));
                    }
                }
                // If there are valid calls, execute them
                if (!validCalls.isEmpty()) {
                    toolCalls = validCalls;
                    // Continue to normal execution path below, but add error results after
                } else {
                    // All calls are invalid — return all errors and continue
                    turnMessages.addAll(errorResults);
                    turnIndex++;
                    continue;
                }
                // Add error results for invalid calls before proceeding with valid ones
                turnMessages.addAll(errorResults);
            }

            // 2. Validate JSON arguments — detect truncation and invalid JSON
            ToolCallValidator.JsonValidationResult jsonResult = ToolCallValidator.validateJsonArgs(toolCalls);
            if (!jsonResult.isValid()) {
                if (jsonResult.truncated()) {
                    log.warn("Truncated tool call arguments detected — refusing to execute.");
                    // On truncation, stop as partial (like Hermes)
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
                    }
                    return TurnResult.error("Response truncated due to output length limit");
                }
                log.warn("Invalid JSON in tool call arguments: {}", jsonResult.errors());
                // Inject recovery tool results (preserves role alternation)
                List<Message> errorResults = new ArrayList<>();
                for (ToolCall tc : toolCalls) {
                    boolean hasError = jsonResult.errors().stream()
                        .anyMatch(e -> e.contains("'" + tc.name() + "'"));
                    String content = hasError
                        ? "Error: Invalid JSON arguments. Please retry with valid JSON. For tools with no required parameters, use an empty object: {}."
                        : "Skipped: other tool call in this response had invalid JSON.";
                    errorResults.add(Message.toolResult(tc.id(), content, currentTurnIndex));
                }
                turnMessages.addAll(errorResults);
                turnIndex++;
                continue;
            }

            // 3. Post-call guardrails: cap delegate_task calls, deduplicate
            toolCalls = ToolCallValidator.capDelegateTaskCalls(toolCalls);
            toolCalls = ToolCallValidator.deduplicateToolCalls(toolCalls);

            List<Message> toolResults;

            // ── Parallel-safety gate (parity with Hermes _should_parallelize_tool_batch) ──
            // Even for multiple tool calls, fall back to sequential when the batch
            // isn't safe to parallelise (clarify, overlapping paths, unknown tools, etc.).
            boolean shouldParallel = ToolParallelSafety.shouldParallelize(toolCalls, registeredToolNames);

            if (!shouldParallel) {
                // Sequential path for single tool call or non-parallel-safe batches
                toolResults = new ArrayList<>();
                for (ToolCall call : toolCalls) {
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
                // Check approval flow — use latch-based wait instead of busy-wait
                // Skip the approval gate entirely when the session metadata has
                // subagent_auto_approve=true (set by DelegateTaskTool when
                // agent.delegation.subagent-auto-approve is enabled).
                boolean skipApproval = "true".equals(session.getMetadata("subagent_auto_approve"));
                // F16 fix: create the request when the guardrail flags the tool — the
                // queue never had a producer, so isPending alone was always false.
                boolean approvalRequired = !skipApproval && approvalQueue != null
                    && (approvalQueue.isPending(session.id())
                        || (toolGuardrails != null && toolGuardrails.requiresApproval(call)
                            && approvalQueue.getPending(session.id()) == null
                            && toolGuardrails.requestApproval(session.id(), call) != null));
                if (approvalRequired) {
                    log.info("Tool {} requires approval for session {}, waiting...", call.name(), session.id());
                    long approvalTimeoutMs = java.time.Duration.ofMinutes(5).toMillis();
                    boolean decided = approvalQueue.awaitDecision(session.id(), approvalTimeoutMs);
                    if (!decided) {
                        log.warn("Approval wait timed out for session {} after {} ms", session.id(), approvalTimeoutMs);
                    }
                    // After interrupt, check interrupt flag and skip execution
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Session {} interrupted while waiting for approval", session.id());
                        ToolResult deniedResult = ToolResult.fail("Approval wait interrupted");
                        toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(deniedResult), currentTurnIndex));
                        approvalQueue.clear(session.id());
                        turnMessages.addAll(toolResults);
                        turnIndex++;
                        continue;
                    }
                    if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                        log.info("Session {} interrupted while waiting for approval", session.id());
                        ToolResult deniedResult = ToolResult.fail("Approval wait interrupted");
                        toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(deniedResult), currentTurnIndex));
                        approvalQueue.clear(session.id());
                        turnMessages.addAll(toolResults);
                        turnIndex++;
                        continue;
                    }
                }
                // HERMES-SYNC (tools/approval.py:2984): fail-closed post-wait re-validation —
                // execute ONLY on explicit approval; timeout-without-response blocks too.
                if (approvalRequired && !approvalQueue.isApproved(session.id())) {
                    boolean denied = approvalQueue.isDenied(session.id());
                    String why = denied
                        ? "Tool execution denied by user approval"
                        : "Approval wait timed out without a user decision — tool blocked (fail-closed). "
                          + "Re-request approval if this action is still needed.";
                    log.info("Tool {} {} for session {}, skipping", call.name(),
                        denied ? "denied" : "unapproved after timeout", session.id());
                    ToolResult deniedResult = ToolResult.fail(why);
                    toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(deniedResult), currentTurnIndex));
                    approvalQueue.clear(session.id());
                } else {
                    long toolStart = System.currentTimeMillis();
                    // L6: Reset skill counter BEFORE execution (parity with Hermes
                    // which resets _iters_since_skill before the tool runs, not after).
                    if ("skill_manage".equals(call.name())) {
                        itersSinceSkill.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                    }
                    // C4: Reset memory turn counter when the memory tool is called,
                    // so the next nudge interval starts fresh after actual memory use.
                    if ("memory".equals(call.name())) {
                        turnsSinceMemory.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                    }
                    ToolResult result = toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState);
                    long duration = System.currentTimeMillis() - toolStart;
                    budget = iterationBudget.recordToolExecution(budget, call.name(), duration);
                    log.debug("Tool {} executed in {} ms: success={}, content length={}, error={}",
                        call.name(), duration, result.success(),
                        result.content() != null ? result.content().length() : 0, result.error());
                    toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(result), currentTurnIndex));
                }
                }
            } else {
                // Parallel path for multiple parallel-safe tool calls
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
                // L6: Reset skill counter BEFORE execution (parity with Hermes).
                // In the parallel path, reset before executeToolsInParallel runs.
                // C4: Also reset memory turn counter when the memory tool is called.
                for (ToolCall call : toolCalls) {
                    if ("skill_manage".equals(call.name())) {
                        itersSinceSkill.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                    }
                    if ("memory".equals(call.name())) {
                        turnsSinceMemory.computeIfAbsent(session.id(), k -> new AtomicInteger(0)).set(0);
                    }
                }
                toolResults = executeToolsInParallel(toolCalls, session, turnState, currentTurnIndex);
                for (ToolCall call : toolCalls) {
                    budget = iterationBudget.recordToolExecution(budget, call.name(), 0);
                }
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt after parallel tool execution for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
            }
            // Inject pending steer note into the last tool result
            String steerText = steerBuffer.consume(session.id());
            if (steerText != null && !toolResults.isEmpty()) {
                // M8: Sanitize steer text — strip any steer marker strings to prevent injection
                String sanitizedSteer = steerText
                    .replace(DefaultPromptBuilder.STEER_MARKER_OPEN, "")
                    .replace(DefaultPromptBuilder.STEER_MARKER_CLOSE, "");
                Message lastToolResult = toolResults.get(toolResults.size() - 1);
                String enhancedContent = lastToolResult.content() + "\n\n"
                    + DefaultPromptBuilder.STEER_MARKER_OPEN + "\n" + sanitizedSteer + "\n"
                    + DefaultPromptBuilder.STEER_MARKER_CLOSE;
                toolResults.set(toolResults.size() - 1,
                    Message.toolResult(lastToolResult.toolCallId(), enhancedContent, currentTurnIndex));
                log.info("Injected steer note for session {}", session.id());
            }
            turnMessages.addAll(toolResults);
            // Hermes empty-recovery driver: the previous round landed tool calls.
            lastResponseHadToolCalls = true;

            // P1-5: Persist tool result messages immediately after the batch completes.
            // Mirrors Hermes _persist_session after _execute_tool_calls.
            // If the JVM crashes after tool execution but before the next model call,
            // all tool results are preserved in the database.
            if (midTurnPersistenceCallback != null) {
                // M6: Only advance cursor if persistence succeeded
                if (midTurnPersistenceCallback.persistNewMessages(session.id(), turnMessages, persistedUpTo)) {
                    persistedUpTo = turnMessages.size();
                }
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
        FallbackModelCaller.ModelCallContext callCtx = new FallbackModelCaller.ModelCallContext(
            modelClient, fallbackManager);
        // Adopt a previously-activated fallback client from an earlier turn iteration.
        callCtx.activeClient = activeModelClient;
        ChatResponse response = fallbackModelCaller().call(callCtx, context, tools, session, options);
        // Persist a fallback activated during this call for the rest of the turn.
        activeModelClient = callCtx.activeClient;
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
        long remaining = delayMs;
        while (remaining > 0) {
            long chunk = Math.min(200, remaining);
            Thread.sleep(chunk);
            remaining -= chunk;
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    // c1: message/tool recovery helpers (extractRetryAfterMs, lowerMessageContains,
    // stripGrammarPatternsFromTools, detectRefusalPattern, thinking/image/multimodal
    // contains+strip pairs) moved to TurnExecutor as public statics — dead local
    // copies removed after FallbackModelCaller extraction.
    /**
     * Budget-exhaustion summary — mirrors Hermes {@code _handle_max_iterations}.
     * <p>
     * When the iteration budget is exhausted, instead of just printing a raw
     * "budget exhausted" message, make one extra LLM call with tools stripped
     * and ask the model to summarise what it accomplished and what remains.
     * The summary replaces the bare budget-exhausted text as the final response.
     * <p>
     * If the extra LLM call fails for any reason, returns null and the caller
     * falls back to the plain budget-exhausted message.
     */
    private String requestBudgetExhaustionSummary(Session session, List<Message> turnMessages,
                                                   ModelRequestOptions options) {
        String summaryPrompt =
            "You've reached the maximum number of tool-calling iterations allowed. " +
            "Please provide a final response summarizing what you've found and accomplished so far, " +
            "without calling any more tools.";

        List<Message> summaryMessages = new ArrayList<>(turnMessages);
        summaryMessages.add(Message.user(summaryPrompt));

        try {
            // Call model with NO tools — the model must produce a text summary, not tool calls
            ModelClient client = activeModelClient != null ? activeModelClient : modelClient;
            ChatResponse response = client.complete(summaryMessages, List.of(), options);
            if (response != null && response.content() != null && !response.content().isBlank()) {
                log.info("Budget exhaustion summary generated for session {}", session.id());
                return response.content().trim();
            }
        } catch (Exception e) {
            log.warn("Budget exhaustion summary call failed for session {}: {}", session.id(), e.getMessage());
        }
        return null;
    }

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

    private List<Message> executeToolsInParallel(List<ToolCall> toolCalls, Session session,
                                                  TurnState turnState, int currentTurnIndex) {
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        // M17: Use shared executor instead of creating one per tool batch
        for (ToolCall call : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return toolExecutionService.execute(call.name(), call.id(),
                        call.arguments(), null, session, turnState);
                } catch (Exception e) {
                    log.warn("Tool {} failed in parallel execution: {}", call.name(), e.getMessage());
                    return ToolResult.fail("Tool execution failed: " + call.name() + " - " + e.getMessage());
                }
            }, parallelToolExecutor));
        }

        // Wait for all futures to complete
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        try {
            allOf.join();
        } catch (CompletionException e) {
            log.warn("Parallel tool execution had unexpected error", e);
        }

        // If interrupted, cancel remaining futures
        if (Thread.currentThread().isInterrupted()) {
            for (CompletableFuture<ToolResult> f : futures) {
                f.cancel(true);
            }
        }

        // Collect results in order (preserve tool call ID ordering)
        List<Message> toolResults = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall call = toolCalls.get(i);
            ToolResult result;
            CompletableFuture<ToolResult> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally()) {
                result = future.getNow(ToolResult.fail("Tool not completed: " + call.name()));
            } else {
                result = ToolResult.fail("Tool cancelled: " + call.name());
            }
            log.debug("Parallel tool {} result: success={}, content length={}, error={}",
                call.name(), result.success(),
                result.content() != null ? result.content().length() : 0, result.error());
            toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(result), currentTurnIndex));
        }
        return toolResults;
    }

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