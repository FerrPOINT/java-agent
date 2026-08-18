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
import com.azhukov.agent.core.model.ChatResponse;
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
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolCallValidator;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolParallelSafety;
import com.azhukov.agent.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAgentRuntime implements AgentRuntime {

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
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
    private final com.azhukov.agent.security.ApprovalQueue approvalQueue;
    private final MemoryManager memoryManager;
    private final TokenEstimator tokenEstimator;
    private final ToolResultFormatter toolResultFormatter;
    private final MidTurnPersistenceCallback midTurnPersistenceCallback;
    private final CommentaryCallback commentaryCallback;
    private final MemoryNudgeManager memoryNudgeManager;

    // ── Shared turn-execution logic (c2: extracted from this class) ──
    // TurnExecutor contains the shared model-call-with-retry, tool execution,
    // think-block stripping, and context-compression-check logic that was
    // previously duplicated between DefaultAgentRuntime and AgentStreamingService.
    // Constructed from existing dependencies in @PostConstruct to preserve the
    // @RequiredArgsConstructor signature (tests construct this class positionally).
    private TurnExecutor turnExecutor;

    @PostConstruct
    void initTurnExecutor() {
        this.turnExecutor = new TurnExecutor(
            errorClassifier, properties, contextCompressor, contextEngine,
            toolExecutionService, toolResultFormatter, tokenEstimator,
            interruptToken, approvalQueue, memoryNudgeManager, steerBuffer);
    }

    // Fallback manager — created per-turn, manages mid-turn model switching.
    // Mirrors Hermes _fallback_chain / _fallback_index / _fallback_activated.
    private FallbackManager fallbackManager;

    // Active model client — may be swapped to a fallback client mid-turn.
    private ModelClient activeModelClient;

    // Shared daemon executor for memory sync — avoids creating a new executor every turn
    // Virtual threads are daemon by default in Java 25
    private final ExecutorService memorySyncExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("memory-sync-", 0).factory());

    // M17: Shared executor for parallel tool execution — avoids creating a new executor per batch
    private final ExecutorService parallelToolExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("tool-parallel-", 0).factory());

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
        // Acquire per-session lock to prevent concurrent turns on the same session
        UUID sid = session.id();
        java.util.concurrent.locks.ReentrantLock lock = sessionLocks.computeIfAbsent(sid, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
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

        // Nudge: initialize + increment per-session memory turn counter
        // H6: Only increment if the memory toolset is actually available to the session.
        // M8: On first turn for a session (restart), hydrate the counter from prior
        // user turns in the conversation history so the nudge interval is preserved
        // across restarts. Mirrors Hermes which initializes _turns_since_memory from
        // the persisted conversation length on session load.
        if (effectiveToolsets.contains("memory") && memoryNudgeManager != null) {
            try {
                long priorUserTurns = contextEngine.countPriorUserMessages(sessionIdUuid);
                memoryNudgeManager.initMemoryCounter(sessionIdUuid, priorUserTurns);
            } catch (Exception e) {
                memoryNudgeManager.initMemoryCounter(sessionIdUuid, 0);
            }
            memoryNudgeManager.incrementMemoryTurns(sessionIdUuid);
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
        // Empty response retry counter (parity with Hermes _empty_content_retries: max 3)
        int retryStateEmptyResponse = 0;

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
            if (effectiveToolsets.contains("skills") && memoryNudgeManager != null) {
                memoryNudgeManager.incrementSkillIters(session.id());
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
                if (memoryNudgeManager != null) {
                    memoryNudgeManager.triggerNudgedBackgroundReview(session, turnMessages, interrupted);
                }
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, true, TurnExitReason.BUDGET_EXHAUSTED);
                }
                return new TurnResult(turnMessages, true, null);
            }

            List<Message> context = contextEngine.prepareContext(session, turnMessages);
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
                turnMessages.add(Message.assistant(e.getMessage(), turnIndex));
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
            if (ThinkBlockProcessor.hasIncompleteScratchpad(response.content())) {
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

            if (!response.hasToolCalls()) {
                // ── Thinking-only prefill continuation (parity with Hermes conversation_loop.py:4136) ──
                // If content is ONLY think blocks with no visible text after stripping,
                // don't break the loop — add the assistant message as-is and continue.
                // The model sees its own reasoning on the next iteration and produces visible text.
                // Limit to 2 retries (matching Hermes _thinking_prefill_retries < 2).
                if (response.content() != null
                    && ThinkBlockProcessor.containsAnyThinkTag(response.content())
                    && !ThinkBlockProcessor.hasContentAfterThinkBlock(response.content())
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
                if (ThinkBlockProcessor.isThinkingBudgetExhausted(response) && thinkingPrefillRetries >= 2) {
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
                String visibleContent = ThinkBlockProcessor.stripThinkBlocksFromString(response.content());
                if (visibleContent != null) {
                    visibleContent = visibleContent.strip();
                }
                String rawContent = response.content() != null ? response.content() : "";
                boolean isTrulyEmpty = (visibleContent == null || visibleContent.isBlank())
                    && !ThinkBlockProcessor.containsAnyThinkTag(rawContent);
                boolean prefillExhausted = thinkingPrefillRetries >= 2;
                if (isTrulyEmpty && (!ThinkBlockProcessor.containsAnyThinkTag(rawContent) || prefillExhausted)) {
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
                    int emptyRetries = retryStateEmptyResponse;  // read current value
                    if (emptyRetries < 3) {
                        emptyRetries++;
                        retryStateEmptyResponse = emptyRetries;
                        log.warn("Empty response (no content or reasoning) — retry {}/3 (model returned empty)", emptyRetries);
                        if (emptyRetries == 1) {
                            // First attempt: add a nudge user message
                            turnMessages.add(Message.user(
                                "Please provide your response. Your previous response was empty."));
                        } else if (emptyRetries == 2) {
                            // Second attempt: add a prefill assistant message
                            turnMessages.add(Message.assistant("Let me continue... ", turnIndex));
                            turnMessages.add(Message.user("Please complete your response."));
                        }
                        // Third attempt: fall through to terminal below
                        if (emptyRetries < 3) {
                            turnIndex++;
                            continue;
                        }
                    }
                    // Third attempt (or exhausted): treat as terminal
                    log.error("Empty response after 3 retries — returning terminal EMPTY_RESPONSE_EXHAUSTED");
                    turnMessages.add(Message.assistant("(empty)", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.EMPTY_RESPONSE_EXHAUSTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
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
                if (memoryNudgeManager != null) {
                    memoryNudgeManager.triggerNudgedBackgroundReview(session, turnMessages, interrupted);
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

            // ── Tool execution (c2: delegated to TurnExecutor) ──
            // TurnExecutor.executeToolBatch handles the parallel-safety gate,
            // interrupt checks, approval flow, memory-nudge counter resets,
            // sequential/parallel execution, and steer-note injection.
            boolean skipApproval = "true".equals(session.getMetadata("subagent_auto_approve"));
            TurnExecutor.ToolBatchResult batchResult = turnExecutor.executeToolBatch(
                toolCalls, registeredToolNames, session, turnState, currentTurnIndex, skipApproval);
            if (batchResult.isInterrupted()) {
                turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                }
                return new TurnResult(turnMessages, true, null);
            }
            List<Message> toolResults = batchResult.toolResults();
            // Record tool execution budget for each call
            for (ToolCall call : toolCalls) {
                budget = iterationBudget.recordToolExecution(budget, call.name(), 0);
            }
            turnMessages.addAll(toolResults);

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

            // ── Proactive compression (post-tool-batch) (c2: delegated to TurnExecutor) ──
            // Mirrors Hermes conversation_loop.py:3960-3998: after each tool batch,
            // before the next model call, check if compression should be triggered.
            // This is IN ADDITION to the existing reactive compression on CONTEXT_OVERFLOW.
            turnExecutor.checkProactiveCompression(turnMessages);
        }

        // H7: Fire background review on max-turns-reached path too.
        boolean interrupted = interruptToken != null && interruptToken.isCancelled(session.id());
        if (memoryNudgeManager != null) {
            memoryNudgeManager.triggerNudgedBackgroundReview(session, turnMessages, interrupted);
        }

        if (turnFinalizer != null) {
            turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
        }
        return TurnResult.error("Reached max turns without completion");
    }

    /**
     * Calls modelClient.complete() with retry logic based on ErrorClassifier.
     * <p>
     * Delegates to {@link TurnExecutor#callModelWithRetry} which contains the
     * shared retry logic: error classification, one-shot recovery guards
     * (14 total, parity with Hermes), fallback chain activation, interruptible
     * backoff, and context compression on overflow.
     * <p>
     * This method is retained for backward compatibility (tests reference it via
     * reflection) and to bridge the per-turn {@link FallbackManager} /
     * {@code activeModelClient} state into the stateless {@code TurnExecutor}.
     */
    private ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools, Session session,
                                             ModelRequestOptions options) {
        TurnExecutor.FallbackContext fallbackCtx = new TurnExecutor.FallbackContext(modelClient);
        fallbackCtx.setFallbackManager(fallbackManager);
        fallbackCtx.setActiveModelClient(activeModelClient);
        ChatResponse response = turnExecutor.callModelWithRetry(context, tools, session, options, fallbackCtx);
        // Propagate any model swap done by TurnExecutor back to this runtime's fields
        activeModelClient = fallbackCtx.getActiveModelClient();
        return response;
    }

    /**
     * Attempt to activate the next fallback model in the chain.
     * <p>
     * Delegates to {@link TurnExecutor}'s internal fallback activation via the
     * {@link TurnExecutor.FallbackContext}. Retained for any callers that still
     * reference it directly; the primary retry path now lives in {@code TurnExecutor}.
     */
    private boolean tryActivateFallback(ErrorClassifier.ErrorType errorType, Exception error) {
        // No-op bridge: the real activation now happens inside TurnExecutor via
        // the FallbackContext passed to callModelWithRetry. This method is kept
        // only for backward compatibility with any direct callers.
        return false;
    }

    /**
     * Sleep for the given delay in 200ms increments, checking for thread interrupts
     * between each chunk. Mirrors Hermes conversation_loop.py:1324-1347 backoff sleep.
     * <p>
     * Delegates to {@link TurnExecutor#interruptibleSleep}. Retained as a static
     * method on this class for backward compatibility (RetryHardeningTest calls it).
     *
     * @param delayMs total sleep time in milliseconds
     * @throws InterruptedException if the thread was interrupted during sleep
     */
    static void interruptibleSleep(long delayMs) throws InterruptedException {
        TurnExecutor.interruptibleSleep(delayMs);
    }

    /**
     * Exception thrown for content policy errors (terminal — no retry).
     * <p>
     * Extends {@link TurnExecutor.ContentPolicyException} so that callers catching
     * the TurnExecutor version also catch this one. Retained on this class for
     * backward compatibility (RetryHardeningTest constructs it directly).
     */
    static class ContentPolicyException extends TurnExecutor.ContentPolicyException {
        ContentPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── One-shot recovery guard helpers (c2: moved to TurnExecutor) ──
    // The following helpers were extracted into TurnExecutor and are retained
    // here only as thin delegates so any remaining internal references compile.
    // The canonical implementations now live in TurnExecutor.

    private boolean containsThinkingBlocks(List<Message> context) {
        return TurnExecutor.containsThinkingBlocks(context);
    }

    private boolean containsImageContent(List<Message> context) {
        return TurnExecutor.containsImageContent(context);
    }

    private List<Message> stripImageContent(List<Message> context) {
        return TurnExecutor.stripImageContent(context);
    }

    private boolean containsMultimodalToolContent(List<Message> context) {
        return TurnExecutor.containsMultimodalToolContent(context);
    }

    private List<Message> stripMultimodalToolContent(List<Message> context) {
        return TurnExecutor.stripMultimodalToolContent(context);
    }

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
     * Execute tool calls in parallel (c2: delegated to TurnExecutor).
     * <p>
     * Retained as a thin delegate so any internal callers continue to work;
     * the canonical implementation is in {@link TurnExecutor#executeToolsInParallel}.
     */
    private List<Message> executeToolsInParallel(List<ToolCall> toolCalls, Session session,
                                                  TurnState turnState, int currentTurnIndex) {
        return turnExecutor.executeToolsInParallel(toolCalls, session, turnState, currentTurnIndex);
    }

    /**
     * Estimate output tokens from a ChatResponse (c2: delegated to TurnExecutor).
     */
    private int estimateResponseTokens(ChatResponse response) {
        return TurnExecutor.estimateResponseTokens(response);
    }

    /**
     * WARNING 1: Clean up per-session state maps to prevent memory leaks.
     * <p>
     * Removes entries from {@code sessionLocks} and the memory nudge manager for
     * the given session. This should be called when a
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
        if (memoryNudgeManager != null) {
            memoryNudgeManager.clearSession(sessionId);
        }
        log.debug("Cleaned up runtime state maps for session {}", sessionId);
    }
}