package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.core.budget.IterationBudget.TurnSnapshot;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolParallelSafety;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared turn-execution logic extracted from {@link DefaultAgentRuntime} and
 * {@code AgentStreamingService} to eliminate duplication of the agentic-loop
 * internals.
 * <p>
 * Contains the genuinely shared pieces that both runtimes need:
 * <ul>
 *   <li>{@link #executeToolsInParallel} — parallel tool execution via a shared
 *       virtual-thread executor</li>
 *   <li>{@link #executeToolBatch} — full sequential-or-parallel tool batch
 *       execution with the parallel-safety gate, approval flow, and
 *       memory-nudge counter resets</li>
 *   <li>{@link #checkProactiveCompression} — post-tool-batch context
 *       compression check</li>
 *   <li>{@link #computeBackoffMs} — error classification + backoff calculation
 *       shared by the streaming path (which cannot reuse the synchronous
 *       retry loop directly)</li>
 *   <li>Static helpers: {@link #interruptibleSleep}, {@link #detectRefusalPattern},
 *       {@link #estimateResponseTokens(ChatResponse)},
 *       {@link #estimateResponseTokens(String, List)}, plus think/image/multimodal
 *       content helpers</li>
 * </ul>
 * <p>
 * The synchronous model-call retry loop lives in {@link FallbackModelCaller}.
 */
@Slf4j
@Component
public class TurnExecutor {

    private final AgentProperties properties;
    private final ContextCompressor contextCompressor;
    private final ContextEngine contextEngine;

    private final ToolExecutionService toolExecutionService;
    private final ToolResultFormatter toolResultFormatter;
    private final TokenEstimator tokenEstimator;
    private final InterruptToken interruptToken;
    private final com.azhukov.agent.core.security.ApprovalQueue approvalQueue;
    private final com.azhukov.agent.core.security.ToolGuardrails toolGuardrails;
    private final MemoryNudgeManager memoryNudgeManager;
    private final SteerBuffer steerBuffer;

    /**
     * Shared daemon executor for parallel tool execution — avoids creating a
     * new executor per batch. Virtual threads are daemon by default in Java 25.
     */
    private final ExecutorService parallelToolExecutor =
        java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tool-parallel-", 0).factory());

    @jakarta.annotation.PreDestroy
    void shutdown() {
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

    public TurnExecutor(AgentProperties properties,
                         ContextCompressor contextCompressor,
                         ContextEngine contextEngine,
                         ToolExecutionService toolExecutionService,
                         ToolResultFormatter toolResultFormatter,
                         TokenEstimator tokenEstimator,
                         InterruptToken interruptToken,
                         com.azhukov.agent.core.security.ApprovalQueue approvalQueue,
                         com.azhukov.agent.core.security.ToolGuardrails toolGuardrails,
                         MemoryNudgeManager memoryNudgeManager,
                         SteerBuffer steerBuffer) {
        this.properties = properties;
        this.contextCompressor = contextCompressor;
        this.contextEngine = contextEngine;
        this.toolExecutionService = toolExecutionService;
        this.toolResultFormatter = toolResultFormatter;
        this.tokenEstimator = tokenEstimator;
        this.interruptToken = interruptToken;
        this.approvalQueue = approvalQueue;
        this.toolGuardrails = toolGuardrails;
        this.memoryNudgeManager = memoryNudgeManager;
        this.steerBuffer = steerBuffer;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Budget-exhaustion summary (Hermes _handle_max_iterations parity)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Budget-exhaustion summary — mirrors Hermes {@code _handle_max_iterations}.
     * <p>
     * When the iteration budget is exhausted, instead of just printing a raw
     * "budget exhausted" message, make one extra LLM call with tools stripped
     * and ask the model to summarise what it accomplished and what remains.
     * The summary replaces the bare budget-exhausted text as the final response.
     * <p>
     * c2: single owner — previously sync-only (DefaultAgentRuntime inline);
     * the streaming loop emitted the raw budget message with no summary call.
     *
     * @param activeClient the model client to use (the caller's active/fallback client)
     * @return the trimmed summary, or null when there are no tool results, the call failed, or it was blank
     */
    public String requestBudgetExhaustionSummary(ModelClient activeClient, Session session,
                                                  List<Message> turnMessages,
                                                  com.azhukov.agent.core.client.ModelRequestOptions options) {
        if (turnMessages.stream().noneMatch(message -> message.role() == com.azhukov.agent.core.model.Role.TOOL)) {
            return null;
        }
        String summaryPrompt =
            "You've reached the maximum number of tool-calling iterations allowed. " +
            "Please provide a final response summarizing what you've found and accomplished so far, " +
            "without calling any more tools.";

        List<Message> summaryMessages = new ArrayList<>(turnMessages);
        summaryMessages.add(Message.user(summaryPrompt));

        try {
            // Call model with NO tools — the model must produce a text summary, not tool calls
            ChatResponse response = activeClient.complete(
                HistorySanitizer.sanitizeForModelRequest(summaryMessages), List.of(), options);
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
     * Produces the exact counters and limits when the final toolless summary
     * cannot be obtained from the provider.
     */
    public String formatBudgetExhaustionMessage(TurnSnapshot budget, String reason) {
        var configured = properties.getBudget();
        String tokenLimit = configured.getMaxTokensPerTurn() > 0
            ? String.valueOf(configured.getMaxTokensPerTurn()) : "unlimited";
        String toolDurationLimit = configured.getMaxToolDurationMsPerTurn() > 0
            ? String.valueOf(configured.getMaxToolDurationMsPerTurn()) : "unlimited";
        return "Iteration budget exhausted: reason=" + reason
            + "; model_calls=" + budget.modelCalls() + "/" + configured.getMaxModelCallsPerTurn()
            + "; tool_executions=" + budget.toolExecutions() + "/" + configured.getMaxToolExecutionsPerTurn()
            + "; estimated_tokens=" + (budget.totalInputTokens() + budget.totalOutputTokens())
                + "/" + tokenLimit
            + "; tool_duration_ms=" + budget.totalToolDurationMs()
                + "/" + toolDurationLimit + ".";
    }

    /**
     * Compute the backoff delay for the given error type and attempt index.
     * <p>
     * Shared by both the synchronous retry loop and the streaming retry path.
     *
     * @param errorType the classified error type
     * @param attempt    zero-based attempt index
     * @param e         the original exception (for Retry-After header parsing)
     * @return delay in milliseconds
     */
    public long computeBackoffMs(ErrorClassifier.ErrorType errorType, int attempt, Exception e) {
        long delayMs;
        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
            long retryAfterMs = extractRetryAfterMs(e);
            if (retryAfterMs > 0) {
                // CUSTOM OPERATOR SETTING: providers here can vanish for minutes
                // to half an hour (LiteLLM "Try again in 600 seconds" cooldowns).
                // Honor the provider's own cooldown up to 30 minutes instead of
                // capping at 120s and burning guaranteed-useless attempts.
                delayMs = Math.min(retryAfterMs, 1_800_000L);
                log.info("Rate limit: using Retry-After value: {}ms (capped at 30min)", delayMs);
            } else {
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
        return delayMs;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Tool execution (shared by both runtimes)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Execute a batch of tool calls, choosing sequential or parallel execution
     * via the {@link ToolParallelSafety} gate, and injecting any pending steer
     * note into the last tool result.
     * <p>
     * This is the canonical "tool execution in parallel" (and sequential) path
     * extracted from {@code DefaultAgentRuntime}. It handles:
     * <ul>
     *   <li>Interrupt checks before and during execution</li>
     *   <li>Approval flow (latch-based wait)</li>
     *   <li>Memory-nudge counter resets (skill_manage / memory tools)</li>
     *   <li>Parallel-safety gate: parallel for safe batches, sequential otherwise</li>
     *   <li>Steer-note injection into the last tool result</li>
     * </ul>
     *
     * @param toolCalls         the validated tool calls to execute
     * @param registeredToolNames set of valid tool names (for parallel-safety check)
     * @param session            the current session
     * @param turnState          the current turn state
     * @param currentTurnIndex   the turn index for result messages
     * @param skipApproval       true to skip the approval gate (subagent auto-approve)
     * @return list of tool result messages, or null if the turn was interrupted
     *         (caller should return the interrupted TurnResult)
     */
    /**
     * Optional per-tool progress events, driven from inside the batch
     * executor so SSE surfaces (streaming) and silent execution (sync)
     * traverse the identical gate/order.
     */
    public interface ToolBatchEvents {
        default void onToolStart(ToolCall call) {}
        default void onToolResult(ToolCall call, ToolResult result, String formatted) {}
    }

    /**
     * One executed tool call for post-batch accounting. A human wait in
     * blocking clarify counts as one tool interaction but never as execution
     * time against the turn's tool-duration limit.
     */
    public record ToolExecutionRecord(String toolName, long durationMs, boolean refunded, boolean chargesDurationBudget) {}

    /**
     * c2 canonical batch executor. Both the sync loop (DefaultAgentRuntime) and
     * the streaming loop (AgentStreamingService) dispatch tool batches through
     * this single owner so the approval gate (incl. rev-115 subagent auto-deny
     * and rev-131 single-use approvals), /yolo bypass, interrupt checks, steer
     * injection, result budget enforcement and the execute_code budget refund
     * (Hermes conversation_loop.py:7277-7280, previously streaming-only)
     * behave identically on both surfaces.
     * <p>
     * Budget recording stays with the caller: each executed call is returned in
     * {@link ToolBatchResult#executions()} with its duration and refund flag so
     * the caller applies {@code recordToolExecution}/{@code refundToolExecution}
     * against its own snapshot.
     *
     * @param events optional SSE progress sink (null for the sync path)
     */
    public ToolBatchResult executeToolBatch(List<ToolCall> toolCalls, Set<String> registeredToolNames,
                                             Session session, TurnState turnState, int currentTurnIndex,
                                             boolean skipApproval, ToolBatchEvents events) {
        // P9 parity (tool_executor.py:661,726): EVERY dispatched tool — including
        // concurrent-segment members — traverses the authorization gate before
        // execution. If any call in the batch requires approval, force the
        // sequential path so the per-call approval/wait/re-validate flow runs.
        boolean allExecuteCode = !toolCalls.isEmpty()
            && toolCalls.stream().allMatch(tc -> "execute_code".equals(tc.name()));
        boolean anyApprovalRequired = false;
        if (!skipApproval && approvalQueue != null && toolGuardrails != null) {
            for (ToolCall call : toolCalls) {
                if (toolGuardrails.requiresApproval(call)) {
                    anyApprovalRequired = true;
                    break;
                }
            }
        }
        boolean shouldParallel = !anyApprovalRequired
            && ToolParallelSafety.shouldParallelize(toolCalls, registeredToolNames, session);

        if (!shouldParallel) {
            // Sequential path
            List<Message> toolResults = new ArrayList<>();
            List<ToolExecutionRecord> executions = new ArrayList<>();
            String browserUnavailableReason = null;
            for (ToolCall call : toolCalls) {
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    return new ToolBatchResult(toolResults, true, executions);
                }
                if (browserUnavailableReason != null && isBrowserTool(call.name())) {
                    ToolResult skipped = ToolResult.fail("Skipped " + call.name()
                        + ": browser infrastructure is unavailable (" + browserUnavailableReason
                        + "). Continue with independent tools or restore the browser connection first.");
                    String formatted = toolResultFormatter.formatResult(skipped);
                    toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                    if (events != null) events.onToolResult(call, skipped, formatted);
                    continue;
                }
                // Approval flow — remember whether THIS call was gated so the
                // post-wait re-validation below only fires for gated calls.
                // F16 fix: the gate was dead code — requiresApproval/requestApproval had
                // ZERO callers, so no request was ever created and isPending was always
                // false. Create the request here when the guardrail flags the tool.
                // rev-115 Hermes parity (delegate_tool.py:66-97): subagent child
                // sessions NEVER wait for a user decision. Hermes installs a
                // TLS callback into every worker thread — auto-deny by default
                // ("Deny fast and log loudly... so the caller can surface a
                // real error"), auto-approve only under the opt-in
                // delegation.subagent_auto_approve config. Without this, a
                // child's approval request queued on a session no user ever
                // sees blocked the child 5 minutes per dangerous call before
                // the fail-closed timeout denied it.
                // Calculate this once before the subagent policy check. Only
                // approval-gated calls are dangerous: a child must still be able
                // to use ordinary read-only tools to finish its delegated work.
                var mcpAdmission = toolGuardrails != null ? toolGuardrails.mcpAdmission(call) : null;
                boolean flagRequiresApproval = !skipApproval && toolGuardrails != null
                    && (mcpAdmission != null ? toolGuardrails.requiresApproval(call, mcpAdmission)
                        : toolGuardrails.requiresApproval(call));
                boolean isSubagentChild = !skipApproval && session.getMetadata("delegation_parent_session") != null;
                if (isSubagentChild && flagRequiresApproval) {
                    log.warn("Subagent session {} auto-denied dangerous tool {} (delegation.subagent_auto-approve=false)", session.id(), call.name());
                    ToolResult deniedResult = ToolResult.fail(
                        "Tool execution denied by subagent policy: dangerous command '"
                        + call.name() + "' requires approval, but subagent sessions cannot ask the user. "
                        + "Set agent.delegation.subagent-auto-approve=true to allow subagents to run dangerous commands.");
                    String formatted = toolResultFormatter.formatResult(deniedResult);
                    toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                    if (events != null) events.onToolResult(call, deniedResult, formatted);
                    continue;
                }
                // Fail-closed (tools/approval.py:2984): when the guardrail flags
                // the tool, gate the call even if the approval request could not
                // be created (null producer) — nobody can approve a request that
                // doesn't exist, so the post-wait re-validation denies. The old
                // `requestApproval(...) != null` conjunct EXECUTED the call when
                // creation failed (fail-open).
                if (flagRequiresApproval && approvalQueue != null
                        && approvalQueue.getPending(session.id()) == null) {
                    requestApproval(session.id(), call);
                }
                boolean approvalRequired = !skipApproval && approvalQueue != null
                    && (approvalQueue.isPending(session.id()) || flagRequiresApproval);
                boolean decisionReceived = false;
                if (approvalRequired) {
                    log.info("Tool {} requires approval for session {}, waiting...", call.name(), session.id());
                    long approvalTimeoutMs = java.time.Duration.ofMinutes(5).toMillis();
                    decisionReceived = approvalQueue.awaitDecision(session.id(), approvalTimeoutMs);
                    if (!decisionReceived) {
                        log.warn("Approval wait timed out for session {} after {} ms", session.id(), approvalTimeoutMs);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Session {} interrupted while waiting for approval", session.id());
                        ToolResult deniedResult = ToolResult.fail("Approval wait interrupted");
                        String formatted = toolResultFormatter.formatResult(deniedResult);
                        toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                        approvalQueue.clear(session.id());
                        if (events != null) events.onToolResult(call, deniedResult, formatted);
                        return new ToolBatchResult(toolResults, true, executions);
                    }
                    if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                        log.info("Session {} interrupted while waiting for approval", session.id());
                        ToolResult deniedResult = ToolResult.fail("Approval wait interrupted");
                        String formatted = toolResultFormatter.formatResult(deniedResult);
                        toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                        approvalQueue.clear(session.id());
                        if (events != null) events.onToolResult(call, deniedResult, formatted);
                        return new ToolBatchResult(toolResults, true, executions);
                    }
                }
                // HERMES-SYNC (tools/approval.py:2984): post-debounce re-validation,
                // fail-closed — execute ONLY on an explicit approval. A timeout without
                // a user response still blocks the action ("no response" is not a
                // denial, but it is not a consent either); the old gate (isDenied →
                // else execute) let pending/timed-out approvals through (fail-open).
                if (approvalRequired && (!decisionReceived || !approvalQueue.isApproved(session.id()))) {
                    boolean denied = decisionReceived && approvalQueue.isDenied(session.id());
                    String why = denied
                        ? "Tool execution denied by user approval"
                        : "Approval wait timed out without a user decision — tool blocked (fail-closed). "
                          + "Re-request approval if this action is still needed.";
                    log.info("Tool {} {} for session {}, skipping", call.name(),
                        denied ? "denied" : "unapproved after timeout", session.id());
                    ToolResult deniedResult = ToolResult.fail(why);
                    String formatted = toolResultFormatter.formatResult(deniedResult);
                    toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                    approvalQueue.clear(session.id());
                    if (events != null) events.onToolResult(call, deniedResult, formatted);
                } else {
                    // rev-131 Hermes parity ('once' semantics, approval.py:4368):
                    // a user approval is SINGLE-USE. The approved entry must be
                    // consumed by the execution it authorized — otherwise it
                    // stays in the map forever and every later dangerous call
                    // (any tool) skips the prompt via getPending()!=null and
                    // executes without consent. Fail-open lived here.
                    if (approvalRequired) {
                        approvalQueue.clear(session.id());
                    }
                    // Reset skill/memory counters before execution
                    resetNudgeCounters(call, session.id());
                    if (events != null) events.onToolStart(call);
                    long toolStart = System.currentTimeMillis();
                    ToolResult result = mcpAdmission == null
                        ? toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState)
                        : toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState,
                            mcpAdmission);
                    long duration = System.currentTimeMillis() - toolStart;
                    String formatted = toolResultFormatter.formatResult(result);
                    toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                    // Hermes parity (conversation_loop.py:7277-7280): execute_code
                    // executions are refunded — programmatic calls are cheap RPCs
                    // and must not starve the per-turn budget. Previously this
                    // refund existed ONLY on the streaming path.
                    boolean refunded = allExecuteCode && "execute_code".equals(call.name());
                    boolean chargesDurationBudget = !"clarify".equals(call.name());
                    executions.add(new ToolExecutionRecord(call.name(), duration, refunded, chargesDurationBudget));
                    if (events != null) events.onToolResult(call, result, formatted);
                    if (isBrowserInfrastructureFailure(call.name(), result)) {
                        browserUnavailableReason = browserFailureReason(result);
                    }
                }
            }
            // Enforce the aggregate tool-result budget BEFORE steer injection so
            // an injected steer note can never be truncated away (streaming
            // semantics: enforce the batch, then append the steer).
            List<Message> bounded = toolExecutionService.enforceToolResultBudget(toolResults);
            injectSteer(bounded, session.id(), currentTurnIndex);
            return new ToolBatchResult(bounded, false, executions);
        } else {
            // Parallel path. execute_code refunds do not reach here in practice
            // (a single execute_code call is not parallel-safe), but the flag is
            // still computed uniformly.
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Turn cancelled by interrupt for session {}", session.id());
                return ToolBatchResult.interruptedResult();
            }
            // Reset skill/memory counters before parallel execution
            for (ToolCall call : toolCalls) {
                resetNudgeCounters(call, session.id());
                if (events != null) events.onToolStart(call);
            }
            List<ToolExecutionRecord> executions = new ArrayList<>();
            List<Message> toolResults = executeToolsInParallel(toolCalls, session, turnState, currentTurnIndex);
            for (int i = 0; i < toolCalls.size(); i++) {
                ToolCall call = toolCalls.get(i);
                Message msg = i < toolResults.size() ? toolResults.get(i) : null;
                executions.add(new ToolExecutionRecord(call.name(), 0,
                    allExecuteCode && "execute_code".equals(call.name()), !"clarify".equals(call.name())));
                if (events != null && msg != null) {
                    events.onToolResult(call, ToolResult.ok(msg.content() != null ? msg.content() : ""), msg.content());
                }
            }
            if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                log.info("Turn cancelled by interrupt after parallel tool execution for session {}", session.id());
                injectSteer(toolResults, session.id(), currentTurnIndex);
                return new ToolBatchResult(toolResults, true, executions);
            }
            // Budget enforcement before steer injection — see sequential path.
            List<Message> boundedParallel = toolExecutionService.enforceToolResultBudget(toolResults);
            injectSteer(boundedParallel, session.id(), currentTurnIndex);
            return new ToolBatchResult(boundedParallel, false, executions);
        }
    }

    /**
     * Result of executing a tool batch.
     * <p>
     * When {@code interrupted} is true, the caller should return an interrupted
     * {@code TurnResult} (the {@code toolResults} may contain partial results
     * that were already generated before the interrupt).
     * <p>
     * {@code executions} carries per-call budget records (name, duration,
     * refund flag) so the owning loop applies iteration-budget accounting
     * against its own snapshot.
     */
    public static final class ToolBatchResult {
        private final List<Message> toolResults;
        private final boolean interrupted;
        private final List<ToolExecutionRecord> executions;

        public ToolBatchResult(List<Message> toolResults, boolean interrupted) {
            this(toolResults, interrupted, List.of());
        }

        public ToolBatchResult(List<Message> toolResults, boolean interrupted, List<ToolExecutionRecord> executions) {
            this.toolResults = toolResults;
            this.interrupted = interrupted;
            this.executions = executions != null ? executions : List.of();
        }

        public List<Message> toolResults() { return toolResults; }
        public boolean isInterrupted() { return interrupted; }
        public List<ToolExecutionRecord> executions() { return executions; }

        public static ToolBatchResult interruptedResult() {
            return new ToolBatchResult(List.of(), true, List.of());
        }
    }

    /**
     * Execute tool calls in parallel using the shared virtual-thread executor.
     * <p>
     * Waits for all futures, cancels remaining on interrupt, and collects
     * results in tool-call-ID order.
     *
     * @param toolCalls       the tool calls to execute
     * @param session         the current session
     * @param turnState       the current turn state
     * @param currentTurnIndex the turn index for result messages
     * @return tool result messages in the same order as {@code toolCalls}
     */
    public List<Message> executeToolsInParallel(List<ToolCall> toolCalls, Session session,
                                                 TurnState turnState, int currentTurnIndex) {
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(com.azhukov.agent.core.security.UserContext.wrapSupplier(() -> {
                try {
                    return toolExecutionService.execute(call.name(), call.id(),
                        call.arguments(), null, session, turnState);
                } catch (Exception e) {
                    log.warn("Tool {} failed in parallel execution: {}", call.name(), e.getMessage());
                    return ToolResult.fail("Tool execution failed: " + call.name() + " - " + e.getMessage());
                }
            }), parallelToolExecutor));
        }

        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        try {
            allOf.join();
        } catch (CompletionException e) {
            log.warn("Parallel tool execution had unexpected error", e);
        }

        if (Thread.currentThread().isInterrupted()) {
            for (CompletableFuture<ToolResult> f : futures) {
                f.cancel(true);
            }
        }

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
            toolResults.add(Message.toolResult(call.pairingId(), toolResultFormatter.formatResult(result), currentTurnIndex));
        }
        return toolResults;
    }

    /**
     * Inject a pending steer note into the last tool result message, if any.
     * <p>
     * Mirrors Hermes steer-note injection: the steer text is appended to the
     * last tool result so the model sees it on the next iteration.
     */
    private void injectSteer(List<Message> toolResults, UUID sessionId, int currentTurnIndex) {
        if (steerBuffer == null || toolResults.isEmpty()) return;
        String steerText = steerBuffer.consume(sessionId);
        if (steerText == null) return;
        String sanitizedSteer = steerText
            .replace(DefaultPromptBuilder.STEER_MARKER_OPEN, "")
            .replace(DefaultPromptBuilder.STEER_MARKER_CLOSE, "");
        Message lastToolResult = toolResults.get(toolResults.size() - 1);
        String enhancedContent = lastToolResult.content() + "\n\n"
            + DefaultPromptBuilder.STEER_MARKER_OPEN + "\n" + sanitizedSteer + "\n"
            + DefaultPromptBuilder.STEER_MARKER_CLOSE;
        toolResults.set(toolResults.size() - 1,
            Message.toolResult(lastToolResult.toolCallId(), enhancedContent, currentTurnIndex));
        log.info("steer_injected_at_tool_boundary session={}", sessionId);
    }

    private static boolean isBrowserTool(String toolName) {
        return toolName != null && toolName.startsWith("browser_");
    }

    private static boolean isBrowserInfrastructureFailure(String toolName, ToolResult result) {
        if (!isBrowserTool(toolName) || result == null || result.success()) {
            return false;
        }
        String diagnostic = (result.error() == null ? "" : result.error()) + " "
            + (result.content() == null ? "" : result.content());
        String lower = diagnostic.toLowerCase(Locale.ROOT);
        return lower.contains("connectexception") || lower.contains("connection refused")
            || lower.contains("provider_unavailable") || lower.contains("browser backend unavailable");
    }

    private static String browserFailureReason(ToolResult result) {
        String reason = result.error();
        if (reason == null || reason.isBlank()) reason = result.content();
        if (reason == null || reason.isBlank()) return "connection failure";
        return reason.replaceAll("[\\r\\n]+", " ").trim();
    }

    /**
     * Reset memory-nudge counters for skill_manage / memory tool calls.
     */
    private void resetNudgeCounters(ToolCall call, UUID sessionId) {
        if ("skill_manage".equals(call.name()) && memoryNudgeManager != null) {
            memoryNudgeManager.resetSkillIters(sessionId);
        }
        if ("memory".equals(call.name()) && memoryNudgeManager != null) {
            memoryNudgeManager.resetMemoryTurns(sessionId);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Context compression check (shared by both runtimes)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Check whether proactive context compression should be triggered after a
     * tool batch, and if so, compress the turn messages in place.
     * <p>
     * Mirrors Hermes {@code conversation_loop.py:3960-3998}: after each tool
     * batch, before the next model call, check if compression should be
     * triggered. This is IN ADDITION to the reactive compression on
     * CONTEXT_OVERFLOW handled by the model recovery path.
     * <p>
     * This is the "context compression check" shared by both runtimes.
     *
     * @param turnMessages the mutable turn message list (compressed in place if triggered)
     * @return true if compression was triggered and reduced the message list
     */
    public boolean checkProactiveCompression(List<Message> turnMessages) {
        if (!(contextCompressor instanceof DefaultContextCompressor dcc)
            || !properties.getCompression().isEnabled()) {
            return false;
        }
        int estimatedTokens = tokenEstimator.estimateTokens(turnMessages);
        int contextWindowSize = 0;
        if (contextEngine instanceof DefaultContextEngine dce) {
            contextWindowSize = dce.getContextLength();
        }
        if (contextWindowSize <= 0) {
            contextWindowSize = properties.getContext().getMaxTokens();
        }
        if (!dcc.shouldCompressProactive(estimatedTokens, contextWindowSize)) {
            return false;
        }
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
            return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Shared static helpers
    // ──────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────
    //  Static utility helpers — delegated to TurnExecutorUtils
    //  (kept here as thin delegates for backward API compatibility)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Sleep for the given delay in 200ms increments, checking for thread
     * interrupts between each chunk. Mirrors Hermes backoff sleep.
     *
     * @param delayMs total sleep time in milliseconds
     * @throws InterruptedException if the thread was interrupted during sleep
     * @see TurnExecutorUtils#interruptibleSleep(long)
     */
    public static void interruptibleSleep(long delayMs) throws InterruptedException {
        TurnExecutorUtils.interruptibleSleep(delayMs);
    }

    /**
     * Detect refusal patterns in the error message that indicate a content
     * policy violation. Returns a user-friendly message if a refusal pattern
     * is found, null otherwise.
     *
     * @see TurnExecutorUtils#detectRefusalPattern(String)
     */
    public static String detectRefusalPattern(String message) {
        return TurnExecutorUtils.detectRefusalPattern(message);
    }

    /**
     * Estimate output tokens from a {@link ChatResponse}.
     *
     * @see TurnExecutorUtils#estimateResponseTokens(ChatResponse)
     */
    public static int estimateResponseTokens(ChatResponse response) {
        return TurnExecutorUtils.estimateResponseTokens(response);
    }

    /**
     * Estimate output tokens from raw content + tool calls (streaming path).
     *
     * @see TurnExecutorUtils#estimateResponseTokens(String, List)
     */
    public static int estimateResponseTokens(String content, List<ToolCall> toolCalls) {
        return TurnExecutorUtils.estimateResponseTokens(content, toolCalls);
    }

    /**
     * Strip {@code pattern} and {@code format} JSON Schema keywords from tool schemas.
     *
     * @see TurnExecutorUtils#stripGrammarPatternsFromTools(List)
     */
    public static List<ToolDefinition> stripGrammarPatternsFromTools(List<ToolDefinition> tools) {
        return TurnExecutorUtils.stripGrammarPatternsFromTools(tools);
    }

    /**
     * Strip {@code pattern} and {@code format} JSON Schema keywords from a
     * schema map, recursively. Returns the number of keywords stripped.
     *
     * @see TurnExecutorUtils#stripPatternAndFormat(Map)
     */
    public static int stripPatternAndFormat(Map<String, Object> schema) {
        return TurnExecutorUtils.stripPatternAndFormat(schema);
    }

    /**
     * Check if any message in the context contains thinking/reasoning blocks.
     *
     * @see TurnExecutorUtils#containsThinkingBlocks(List)
     */
    public static boolean containsThinkingBlocks(List<Message> context) {
        return TurnExecutorUtils.containsThinkingBlocks(context);
    }

    /**
     * Check if any message in the context contains image content (imageCount > 0).
     *
     * @see TurnExecutorUtils#containsImageContent(List)
     */
    public static boolean containsImageContent(List<Message> context) {
        return TurnExecutorUtils.containsImageContent(context);
    }

    /**
     * Strip image content from all messages (sets imageCount to 0).
     *
     * @see TurnExecutorUtils#stripImageContent(List)
     */
    public static List<Message> stripImageContent(List<Message> context) {
        return TurnExecutorUtils.stripImageContent(context);
    }

    /**
     * Check if any message in the context contains multimodal tool content
     * (data: URIs with image/ or base64 content).
     *
     * @see TurnExecutorUtils#containsMultimodalToolContent(List)
     */
    public static boolean containsMultimodalToolContent(List<Message> context) {
        return TurnExecutorUtils.containsMultimodalToolContent(context);
    }

    /**
     * Strip multimodal tool content from all messages (replaces data: URIs
     * with a placeholder).
     *
     * @see TurnExecutorUtils#stripMultimodalToolContent(List)
     */
    public static List<Message> stripMultimodalToolContent(List<Message> context) {
        return TurnExecutorUtils.stripMultimodalToolContent(context);
    }

    /**
     * Extract Retry-After header value from an HTTP exception, if available.
     *
     * @param e the exception from the model call
     * @return retry-after value in milliseconds, or -1 if not found
     * @see TurnExecutorUtils#extractRetryAfterMs(Exception)
     */
    public static long extractRetryAfterMs(Exception e) {
        return TurnExecutorUtils.extractRetryAfterMs(e);
    }

    /**
     * Check if an exception's message contains a substring (case-insensitive).
     *
     * @see TurnExecutorUtils#lowerMessageContains(Exception, String)
     */
    public static boolean lowerMessageContains(Exception e, String substring) {
        return TurnExecutorUtils.lowerMessageContains(e, substring);
    }

    /** F16: create an approval request via the guardrail-owned queue path. */
    private com.azhukov.agent.core.security.ApprovalQueue.PendingApproval requestApproval(
            UUID sessionId, ToolCall call) {
        try {
            return toolGuardrails != null
                ? toolGuardrails.requestApproval(sessionId, call)
                : null;
        } catch (Exception e) {
            log.warn("Failed to create approval request for {} in session {}: {}",
                call.name(), sessionId, e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Content policy exception (shared by both runtimes)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Exception thrown for content policy errors (terminal — no retry).
     * The caller catches this to return a user-friendly message.
     */
    public static class ContentPolicyException extends RuntimeException {
        public ContentPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}