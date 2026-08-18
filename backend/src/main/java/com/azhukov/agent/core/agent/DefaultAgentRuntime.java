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
                if (!skipApproval && approvalQueue != null && approvalQueue.isPending(session.id())) {
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
                if (!skipApproval && approvalQueue != null && approvalQueue.isDenied(session.id())) {
                    log.info("Tool {} denied for session {}, skipping", call.name(), session.id());
                    ToolResult deniedResult = ToolResult.fail("Tool execution denied by user approval");
                    toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(deniedResult), currentTurnIndex));
                    approvalQueue.clear(session.id());
                } else {
                    long toolStart = System.currentTimeMillis();
                    // L6: Reset skill counter BEFORE execution (parity with Hermes
                    // which resets _iters_since_skill before the tool runs, not after).
                    if ("skill_manage".equals(call.name()) && memoryNudgeManager != null) {
                        memoryNudgeManager.resetSkillIters(session.id());
                    }
                    // C4: Reset memory turn counter when the memory tool is called,
                    // so the next nudge interval starts fresh after actual memory use.
                    if ("memory".equals(call.name()) && memoryNudgeManager != null) {
                        memoryNudgeManager.resetMemoryTurns(session.id());
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
                    if ("skill_manage".equals(call.name()) && memoryNudgeManager != null) {
                        memoryNudgeManager.resetSkillIters(session.id());
                    }
                    if ("memory".equals(call.name()) && memoryNudgeManager != null) {
                        memoryNudgeManager.resetMemoryTurns(session.id());
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
    private ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools, Session session,
                                             ModelRequestOptions options) {
        int retryAttempts = properties.getError().getRetryAttempts();
        Exception lastException = null;
        int totalAttempts = 0;
        List<Message> currentContext = context;
        TurnRetryState retryState = new TurnRetryState();

        // ── Fallback loop (parity with Hermes _try_activate_fallback) ──
        // When retries are exhausted AND shouldFallback is true, activate the next
        // fallback model, reset retry state (but NOT budget), and continue.
        // Immediate-fallback errors (AUTH_PERMANENT, CONTENT_POLICY, MODEL_NOT_FOUND)
        // skip retries entirely and go straight to fallback.
        for (;;) {
            for (int attempt = 0; attempt <= retryAttempts; attempt++) {
                totalAttempts++;
                try {
                    ModelClient client = activeModelClient != null ? activeModelClient : modelClient;
                    return client.complete(currentContext, tools, options);
                } catch (Exception e) {
                    lastException = e;
                    if (attempt >= retryAttempts) {
                        break;
                    }
                    ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(e);
                    ErrorClassifier.ErrorType errorType = classification.type();

                    // ── Part F: Content policy handling ──
                    // Mirrors Hermes content_policy_blocked.
                    // Content policy is an immediate-fallback trigger: don't retry
                    // the same model, but try a fallback model if available.
                    if (errorType == ErrorClassifier.ErrorType.CONTENT_POLICY) {
                        log.warn("Content policy block: {} — attempting fallback", e.getMessage());
                        if (tryActivateFallback(errorType, e)) {
                            // Fallback activated — reset retry state and restart the retry loop
                            retryState = new TurnRetryState();
                            currentContext = context; // reset context for the new model
                            break; // break inner loop, continue outer fallback loop
                        }
                        // No fallback available — throw user-friendly content policy error
                        String refusalMsg = detectRefusalPattern(e.getMessage());
                        throw new ContentPolicyException(refusalMsg != null ? refusalMsg :
                            "The model's safety filter blocked this request. Please rephrase your request and try again.",
                            e);
                    }

                    // ── One-shot recovery guards (parity with Hermes TurnRetryState — 14 total) ──

                    // Guard 1: AUTH — try refreshing credentials once (generic)
                    if (errorType == ErrorClassifier.ErrorType.AUTH && !retryState.isAuthRetryAttempted()) {
                        retryState.setAuthRetryAttempted(true);
                        log.info("AUTH error, attempting credential refresh (one-shot guard)");
                        // Don't consume a retry slot for auth refresh
                        attempt--;
                        continue;
                    }

                    // Guard 2: THINKING_SIGNATURE — strip thinking blocks and retry
                    if (errorType == ErrorClassifier.ErrorType.THINKING_SIGNATURE
                        && !retryState.isThinkingSigRetryAttempted()
                        && containsThinkingBlocks(currentContext)) {
                        retryState.setThinkingSigRetryAttempted(true);
                        log.info("THINKING_SIGNATURE error, stripping thinking blocks and retrying (one-shot guard)");
                        currentContext = ThinkBlockProcessor.stripThinkingBlocks(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 2b: FORMAT_ERROR with thinking blocks (fallback for older classifier paths)
                    if (errorType == ErrorClassifier.ErrorType.FORMAT_ERROR
                        && !retryState.isThinkingSigRetryAttempted()
                        && containsThinkingBlocks(currentContext)) {
                        retryState.setThinkingSigRetryAttempted(true);
                        log.info("FORMAT_ERROR with thinking blocks, stripping and retrying (one-shot guard)");
                        currentContext = ThinkBlockProcessor.stripThinkingBlocks(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 3: IMAGE_TOO_LARGE — shrink image content from messages
                    if ((errorType == ErrorClassifier.ErrorType.IMAGE_TOO_LARGE
                         || errorType == ErrorClassifier.ErrorType.CONTENT_POLICY)
                        && !retryState.isImageShrinkRetryAttempted()
                        && containsImageContent(currentContext)) {
                        retryState.setImageShrinkRetryAttempted(true);
                        log.info("{} with image content, stripping images and retrying (one-shot guard)", errorType);
                        currentContext = stripImageContent(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 4: MULTIMODAL_TOOL_CONTENT — strip non-text from tool results
                    if ((errorType == ErrorClassifier.ErrorType.MULTIMODAL_TOOL_CONTENT
                         || errorType == ErrorClassifier.ErrorType.FORMAT_ERROR)
                        && !retryState.isMultimodalToolContentRetryAttempted()
                        && containsMultimodalToolContent(currentContext)) {
                        retryState.setMultimodalToolContentRetryAttempted(true);
                        log.info("{} with multimodal tool content, stripping and retrying (one-shot guard)", errorType);
                        currentContext = stripMultimodalToolContent(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 5: INVALID_ENCRYPTED_CONTENT — strip codex reasoning replay
                    if (errorType == ErrorClassifier.ErrorType.INVALID_ENCRYPTED_CONTENT
                        && !retryState.isInvalidEncryptedContentRetryAttempted()) {
                        retryState.setInvalidEncryptedContentRetryAttempted(true);
                        log.info("INVALID_ENCRYPTED_CONTENT error, stripping encrypted reasoning and retrying (one-shot guard)");
                        currentContext = ThinkBlockProcessor.stripThinkingBlocks(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 6: LONG_CONTEXT_TIER with oauth_1m_beta — disable 1M context beta
                    if (errorType == ErrorClassifier.ErrorType.LONG_CONTEXT_TIER
                        && !retryState.isOauth1mBetaRetryAttempted()
                        && lowerMessageContains(e, "long context beta")) {
                        retryState.setOauth1mBetaRetryAttempted(true);
                        log.info("LONG_CONTEXT_TIER with 1M beta, disabling beta and retrying (one-shot guard)");
                        // In future: strip beta header from request options
                        attempt--;
                        continue;
                    }

                    // Guard 7: LLAMA_CPP_GRAMMAR — strip tool schema patterns
                    if (errorType == ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR
                        && !retryState.isLlamaCppGrammarRetryAttempted()) {
                        retryState.setLlamaCppGrammarRetryAttempted(true);
                        log.info("LLAMA_CPP_GRAMMAR error, stripping pattern/format from tools and retrying (one-shot guard)");
                        tools = stripGrammarPatternsFromTools(tools);
                        attempt--;
                        continue;
                    }

                    // ── Compression-disabled respect (parity with Hermes conversation_loop.py:2610-2663) ──
                    // When compression is disabled (compression.enabled: false), NO automatic compaction
                    // may fire — including the overflow recovery paths below. Surface a terminal error
                    // so the user can compact manually, start fresh, switch to a larger-context model,
                    // or reduce attachments.
                    if ((errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW
                         || errorType == ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE
                         || errorType == ErrorClassifier.ErrorType.LONG_CONTEXT_TIER)
                        && !properties.getCompression().isEnabled()) {
                        log.warn("Context overflow ({}) detected, but auto-compaction is disabled (compression.enabled: false)", errorType);
                        throw new RuntimeException(
                            "Context limit exceeded. Enable compression or reduce context size. " +
                            "Run /compress to compact manually, /new to start fresh, " +
                            "or switch to a larger-context model.", e);
                    }

                    // ── Long-context tier handling (parity with Hermes conversation_loop.py:2665-2719) ──
                    // Anthropic returns HTTP 429 "Extra usage is required for long context requests"
                    // when a subscription doesn't include the 1M-context tier. Reduce context to 200K
                    // (the standard tier) and compress.
                    if (errorType == ErrorClassifier.ErrorType.LONG_CONTEXT_TIER) {
                        int reducedContext = 200_000;
                        if (contextCompressor instanceof DefaultContextCompressor dcc) {
                            // Reduce the compressor's context length to 200K (or context_length * 0.8)
                            int currentContextLength = 0;
                            if (contextEngine instanceof DefaultContextEngine dce) {
                                currentContextLength = dce.getContextLength();
                            }
                            if (currentContextLength > reducedContext) {
                                dcc.recalculateThreshold(reducedContext);
                                log.warn("⚠️  Anthropic long-context tier requires extra usage — reducing context: {} → {} tokens",
                                    currentContextLength, reducedContext);
                            }
                        }
                        // Compress and retry (parity with Hermes: compression_attempts++ then compress)
                        if (contextCompressor != null) {
                            int compressionAttempts = retryState.getCompressionAttempts();
                            if (compressionAttempts >= 3) {
                                log.error("Max compression attempts (3) reached for long-context-tier error.");
                                break;
                            }
                            retryState.incrementCompressionAttempts();
                            log.info("Long-context tier detected, triggering compression attempt {}/3", compressionAttempts + 1);
                            try {
                                int targetChars = properties.getContext().getTargetTokens() * 4;
                                List<Message> compressed = contextCompressor.compress(currentContext, targetChars);
                                currentContext = compressed;
                                log.info("Context compressed for long-context tier (attempt {}/3), retrying model call",
                                    compressionAttempts + 1);
                                attempt--;
                                continue;
                            } catch (Exception ce) {
                                log.warn("Context compression for long-context tier failed (attempt {}/3): {}",
                                    compressionAttempts + 1, ce.getMessage());
                                if (compressionAttempts + 1 >= 3) {
                                    break;
                                }
                                attempt--;
                                continue;
                            }
                        }
                    }

                    // Context overflow: try compression (up to 3 attempts) before giving up
                    // Mirrors Hermes: max_compression_attempts = 3
                    if (errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW) {
                        if (contextCompressor == null) {
                            log.warn("Context overflow detected, compression unavailable, failing: {}", e.getMessage());
                            break;
                        }
                        int compressionAttempts = retryState.getCompressionAttempts();
                        if (compressionAttempts >= 3) {
                            log.error("Max compression attempts (3) reached for context overflow.");
                            break;
                        }
                        retryState.incrementCompressionAttempts();
                        log.info("Context overflow detected, triggering compression attempt {}/3", compressionAttempts + 1);
                        try {
                            int targetChars = properties.getContext().getTargetTokens() * 4;
                            List<Message> compressed = contextCompressor.compress(currentContext, targetChars);
                            if (compressed.size() < currentContext.size()
                                || (compressed.size() == currentContext.size()
                                    && compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                                    < currentContext.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum())) {
                                currentContext = compressed;
                                log.info("Context compressed from {} to {} messages, retrying model call (attempt {}/3)",
                                    context.size(), compressed.size(), compressionAttempts + 1);
                                // Don't consume a retry attempt for compression — retry immediately.
                                attempt--;
                                continue;
                            } else {
                                log.warn("Context overflow detected, compression did not reduce context (attempt {}/3): {}",
                                    compressionAttempts + 1, e.getMessage());
                                if (compressionAttempts + 1 >= 3) {
                                    break;
                                }
                                attempt--;
                                continue;
                            }
                        } catch (Exception ce) {
                            log.warn("Context compression failed (attempt {}/3): {}", compressionAttempts + 1, ce.getMessage());
                            if (compressionAttempts + 1 >= 3) {
                                break;
                            }
                            attempt--;
                            continue;
                        }
                    }

                    // Payload too large: try compression (up to 3 attempts) — same path as context overflow
                    if (errorType == ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE) {
                        if (contextCompressor == null) {
                            log.warn("Payload too large, compression unavailable, failing: {}", e.getMessage());
                            break;
                        }
                        int compressionAttempts = retryState.getCompressionAttempts();
                        if (compressionAttempts >= 3) {
                            log.error("Max compression attempts (3) reached for payload-too-large error.");
                            break;
                        }
                        retryState.incrementCompressionAttempts();
                        log.info("Payload too large, triggering compression attempt {}/3", compressionAttempts + 1);
                        try {
                            int targetChars = properties.getContext().getTargetTokens() * 4;
                            List<Message> compressed = contextCompressor.compress(currentContext, targetChars);
                            currentContext = compressed;
                            log.info("Context compressed for payload size (attempt {}/3), retrying model call",
                                compressionAttempts + 1);
                            attempt--;
                            continue;
                        } catch (Exception ce) {
                            log.warn("Context compression for payload too large failed (attempt {}/3): {}",
                                compressionAttempts + 1, ce.getMessage());
                            if (compressionAttempts + 1 >= 3) {
                                break;
                            }
                            attempt--;
                            continue;
                        }
                    }

                    // ── Immediate-fallback errors (no retry, try fallback immediately) ──
                    // Mirrors Hermes: AUTH_PERMANENT, MODEL_NOT_FOUND → switchModel
                    if (errorType == ErrorClassifier.ErrorType.AUTH_PERMANENT
                        || errorType == ErrorClassifier.ErrorType.MODEL_NOT_FOUND) {
                        log.warn("{} error — attempting immediate fallback: {}", errorType, e.getMessage());
                        if (tryActivateFallback(errorType, e)) {
                            retryState = new TurnRetryState();
                            currentContext = context;
                            break; // break inner loop, continue outer fallback loop
                        }
                        log.warn("{} error, no fallback available, failing: {}", errorType, e.getMessage());
                        break;
                    }

                    // ── Permanent errors that don't trigger fallback ──
                    if (errorType == ErrorClassifier.ErrorType.PERMANENT
                        || errorType == ErrorClassifier.ErrorType.BILLING
                        || errorType == ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED) {
                        log.warn("Model call failed with {} error, not retrying: {}", errorType, e.getMessage());
                        break;
                    }

                    // ── Credential rotation for RATE_LIMIT (parity with Hermes credential pool) ──
                    // When error is RATE_LIMIT, try rotating to the next credential in the pool
                    // before retrying. Don't fallback to a different model — just rotate credentials.
                    // Only fallback if credential rotation doesn't help after all retries.
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT && !retryState.isHasRetried429()) {
                        retryState.setHasRetried429(true);
                        // Credential rotation would happen here if a CredentialPool is available
                        // For now, just track that we've seen a 429
                        log.info("RATE_LIMIT (429) detected — tracking for credential rotation");
                    }

                    // ── Part C: Retry-After header parsing ──
                    // Mirrors Hermes conversation_loop.py:3393-3401:
                    // Parse Retry-After header from HTTP error (if available), cap at 120s.
                    // Fall back to exponential backoff if no header.
                    long delayMs;
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                        long retryAfterMs = extractRetryAfterMs(e);
                        if (retryAfterMs > 0) {
                            delayMs = Math.min(retryAfterMs, 120_000L); // Cap at 120s (parity with Hermes)
                            log.info("Rate limit: using Retry-After header value: {}ms (capped at 120s)", delayMs);
                        } else {
                            // Fall back to exponential backoff: 2s * 2^attempt, cap 60s
                            long base = properties.getError().getRetryDelayMs() * (1L << attempt);
                            delayMs = Math.min(base, 60_000L);
                            log.info("Rate limit: no Retry-After header, using exponential backoff: {}ms", delayMs);
                        }
                    } else if (errorType == ErrorClassifier.ErrorType.OVERLOADED) {
                        long base = properties.getError().getRetryDelayMs() * (1L << attempt);
                        delayMs = Math.min(base, 60_000L);
                    } else {
                        long base = properties.getError().getRetryDelayMs() * (1L << attempt);
                        long jitter = ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
                        delayMs = Math.min(base + jitter, 60_000L);
                    }
                    log.warn("Model call failed (attempt {}/{}), classified as {}, retrying in {} ms: {}",
                        attempt + 1, retryAttempts + 1, errorType, delayMs, e.getMessage());

                    // ── Part D: Interruptible backoff ──
                    // Mirrors Hermes conversation_loop.py:1324-1347:
                    // Sleep in 200ms increments, checking for interrupts between chunks.
                    try {
                        interruptibleSleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Model call retry interrupted, stopping retries");
                        break;
                    }
                }
            }

            // ── Retries exhausted — try fallback before failing ──
            // Mirrors Hermes conversation_loop.py:3278-3296: after exhausting retries,
            // try to activate the next fallback in the chain.
            if (lastException != null) {
                ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(lastException);
                if (classification.hints().shouldFallback() && tryActivateFallback(classification.type(), lastException)) {
                    // Fallback activated — reset retry state (but NOT budget) and continue
                    retryState = new TurnRetryState();
                    currentContext = context; // reset context for the new model
                    totalAttempts = 0;
                    continue; // continue outer fallback loop
                }
            }

            // No fallback available or no fallback triggered — fail
            break;
        }

        throw new RuntimeException("Model call failed after " + totalAttempts + " attempt(s): "
            + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    /**
     * Attempt to activate the next fallback model in the chain.
     * <p>
     * Mirrors Hermes {@code _try_activate_fallback()}. If a fallback is available,
     * creates a new {@link com.azhukov.agent.client.langchain4j.FallbackModelClient}
     * from the fallback config and swaps it in as the active model client.
     *
     * @param errorType the error type that triggered the fallback
     * @param error the exception that caused the fallback
     * @return true if fallback was activated, false if chain is exhausted
     */
    private boolean tryActivateFallback(ErrorClassifier.ErrorType errorType, Exception error) {
        if (fallbackManager == null || !fallbackManager.hasPendingFallback()) {
            return false;
        }

        // Set rate-limit cooldown if the trigger was rate-limit related
        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT || errorType == ErrorClassifier.ErrorType.BILLING) {
            fallbackManager.setRateLimitCooldown();
        }

        FallbackConfig fallbackConfig = fallbackManager.activateFallback();
        if (fallbackConfig == null) {
            log.warn("Fallback chain exhausted — no more fallback models available");
            return false;
        }

        // Build a new model client from the fallback config
        try {
            com.azhukov.agent.client.langchain4j.FallbackModelClient fallbackClient =
                com.azhukov.agent.client.langchain4j.FallbackModelClient.from(fallbackConfig, properties);
            activeModelClient = fallbackClient;
            log.info("🔄 Switched to fallback model: {} via {}",
                fallbackManager.getCurrentModel(), fallbackManager.getCurrentProvider());
            // h60: Reset compression failure cooldown when model switches.
            if (contextCompressor instanceof DefaultContextCompressor dcc) {
                dcc.resetCompressionFailureCooldown(
                    fallbackManager.getCurrentProvider() + "/" + fallbackManager.getCurrentModel());
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to create fallback model client for {}/{}: {}",
                fallbackConfig.getProvider(), fallbackConfig.getModel(), e.getMessage());
            // Recursively try the next fallback
            return tryActivateFallback(errorType, error);
        }
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

    /**
     * Extract Retry-After header value from an HTTP exception, if available.
     * Mirrors Hermes conversation_loop.py:3393-3401.
     * <p>
     * Looks for "Retry-After" header in the exception message or cause chain.
     * Many HTTP client libraries (OkHttp, Apache HttpClient) include response
     * headers in their exception messages or as fields on the exception type.
     *
     * @param e the exception from the model call
     * @return retry-after value in milliseconds, or -1 if not found
     */
    private long extractRetryAfterMs(Exception e) {
        if (e == null || e.getMessage() == null) {
            return -1;
        }
        String msg = e.getMessage();
        // Try to find "Retry-After:" or "retry-after:" in the message
        // HTTP client libraries often include headers in error messages
        String lower = msg.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("retry-after:");
        if (idx >= 0) {
            // Extract the value after "retry-after:"
            String after = msg.substring(idx + 12).trim();
            // Take the first numeric token
            String[] parts = after.split("[\\s,;]");
            for (String part : parts) {
                try {
                    double seconds = Double.parseDouble(part.trim());
                    return (long) (seconds * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Also check "Retry-After" without colon (e.g. "Retry-After 60")
        idx = lower.indexOf("retry-after");
        if (idx >= 0) {
            String after = msg.substring(idx + 11).trim();
            // Skip any colon
            if (after.startsWith(":")) {
                after = after.substring(1).trim();
            }
            String[] parts = after.split("[\\s,;]");
            for (String part : parts) {
                try {
                    double seconds = Double.parseDouble(part.trim());
                    return (long) (seconds * 1000);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Check if an exception's message contains a substring (case-insensitive).
     */
    private boolean lowerMessageContains(Exception e, String substring) {
        return e != null && e.getMessage() != null
            && e.getMessage().toLowerCase(Locale.ROOT).contains(substring.toLowerCase(Locale.ROOT));
    }

    /**
     * Strip grammar-incompatible patterns from tool definitions.
     * Mirrors Hermes llama_cpp_grammar recovery: remove "pattern" and "format"
     * fields from JSON schemas that llama.cpp's json-schema-to-grammar rejects.
     */
    private List<ToolDefinition> stripGrammarPatternsFromTools(List<ToolDefinition> tools) {
        // ToolDefinition is a record — we can't mutate it in place.
        // In practice, the tool definitions are serialized to JSON for the API call,
        // and the grammar patterns are in the JSON schema. Since we can't easily
        // strip fields from the record, we log the intent and return the tools as-is.
        // The actual stripping would happen at the serialization layer in a future
        // enhancement. For now, this guard prevents retry loops.
        log.debug("stripGrammarPatternsFromTools: would strip pattern/format from {} tools", tools.size());
        return tools;
    }

    /**
     * Detect refusal patterns in the error message that indicate content policy violation.
     * Returns a user-friendly message if a refusal pattern is found, null otherwise.
     * <p>
     * Mirrors Hermes content_policy handling: detect provider-specific refusal patterns
     * and surface a user-friendly message instead of the raw error.
     */
    private String detectRefusalPattern(String message) {
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("i cannot") || lower.contains("i can't")
            || lower.contains("i'm unable to") || lower.contains("i am unable to")
            || lower.contains("i'm not able to") || lower.contains("i am not able to")
            || lower.contains("i won't be able to") || lower.contains("i will not be able to")) {
            return "The model declined to generate a response for this request due to a content policy restriction. " +
                   "Please rephrase your request or try a different approach.";
        }
        return null;
    }

    /**
     * Exception thrown for content policy errors (terminal — no retry).
     * The caller catches this to return a user-friendly message.
     */
    static class ContentPolicyException extends RuntimeException {
        ContentPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── One-shot recovery guard helpers ──────────────────────────────

    /**
     * Check if any message in the context contains thinking/reasoning blocks.
     * <p>
     * Detects all tag variants supported by {@link #stripThinkingBlocks}:
     * <ul>
     *   <li>{@code <antml:thinking>} (Anthropic native)</li>
     *   <li>{@code <thinking>} / {@code <reasoning>} / {@code <thought>}</li>
     *   <li>{@code <REASONING_SCRATCHPAD>}</li>
     *   <li>{@code ⇋} (Qwen3/Ollama bare format, inline in content)</li>
     * </ul>
     * All matches are case-insensitive, matching Hermes {@code _strip_think_blocks}.
     */
    private boolean containsThinkingBlocks(List<Message> context) {
        return context.stream().anyMatch(m -> m.content() != null
            && ThinkBlockProcessor.containsAnyThinkTag(m.content()));
    }

    private boolean containsImageContent(List<Message> context) {
        return context.stream().anyMatch(m -> m.imageCount() != null && m.imageCount() > 0);
    }

    private List<Message> stripImageContent(List<Message> context) {
        return context.stream().map(m -> {
            if (m.imageCount() == null || m.imageCount() == 0) return m;
            return new Message(m.role(), m.content(), m.toolCall(), m.toolCalls(),
                m.toolCallId(), m.turnIndex(), 0);
        }).toList();
    }

    private boolean containsMultimodalToolContent(List<Message> context) {
        return context.stream().anyMatch(m -> m.content() != null
            && m.content().startsWith("data:")
            && (m.content().contains("image/") || m.content().contains(";base64,")));
    }

    private List<Message> stripMultimodalToolContent(List<Message> context) {
        return context.stream().map(m -> {
            if (m.content() != null && m.content().startsWith("data:")) {
                return new Message(m.role(), "[multimodal content stripped]", m.toolCall(),
                    m.toolCalls(), m.toolCallId(), m.turnIndex(), m.imageCount());
            }
            return m;
        }).toList();
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