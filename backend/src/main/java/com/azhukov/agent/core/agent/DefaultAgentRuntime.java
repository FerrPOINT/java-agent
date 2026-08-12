package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.budget.IterationBudget.TurnSnapshot;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.context.DefaultContextReferenceService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryManager;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.ReviewSummary;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final com.azhukov.agent.core.security.ApprovalQueue approvalQueue;
    private final MemoryManager memoryManager;
    private final TokenEstimator tokenEstimator;
    private final ToolResultFormatter toolResultFormatter;

    // Shared daemon executor for memory sync — avoids creating a new executor every turn
    // Virtual threads are daemon by default in Java 25
    private final ExecutorService memorySyncExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("memory-sync-", 0).factory());

    // M17: Shared executor for parallel tool execution — avoids creating a new executor per batch
    private final ExecutorService parallelToolExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("tool-parallel-", 0).factory());

    @PreDestroy
    void shutdown() {
        memorySyncExecutor.shutdown();
        try {
            if (!memorySyncExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                memorySyncExecutor.shutdownNow();
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
        return modelClient.complete(context, tools);
    }

    @Override
    public TurnResult runTurn(Session session, String userInput, List<String> references,
                              ModelRequestOptions options) {
        ModelRequestOptions effectiveOptions = options != null ? options : ModelRequestOptions.empty();
        return runTurnInternal(session, userInput, references, effectiveOptions);
    }

    private TurnResult runTurnInternal(Session session, String userInput, List<String> references,
                                       ModelRequestOptions options) {
        UUID sessionIdUuid = session.id();
        String sessionId = sessionIdUuid.toString();
        guardrail.reset(sessionIdUuid);
        turnStateManager.clear(sessionIdUuid);
        TurnSnapshot budget = iterationBudget.startTurn(sessionIdUuid);
        String safeInput = inputSanitizer.sanitize(userInput);

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
        turnMessages.add(promptBuilder.buildSystemMessage(session));
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

        List<ToolDefinition> tools = toolRegistry.getDefinitions(new HashSet<>(properties.getSkills().getDefaultToolsets()));
        int maxTurns = properties.getCore().getMaxTurns();
        int turnIndex = 1;

        // Prefetch relevant memories before the turn (A7)
        try {
            memoryProvider.prefetch(safeInput, sessionId);
        } catch (Exception e) {
            log.warn("Memory prefetch failed for session {}: {}", sessionId, e.getMessage());
        }

        TurnResult result = null;
        try {
        result = runTurnLoop(session, turnMessages, tools, maxTurns, turnIndex, budget, turnState, sessionId, sessionIdUuid, options);
        } finally {
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
        return result;
    }

    private TurnResult runTurnLoop(Session session, List<Message> turnMessages, List<ToolDefinition> tools,
                                   int maxTurns, int turnIndex, TurnSnapshot budget, TurnState turnState,
                                   String sessionId, UUID sessionIdUuid, ModelRequestOptions options) {
        for (int i = 0; i < maxTurns; i++) {
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
                turnMessages.add(Message.assistant("Iteration budget exhausted. Stopping to avoid runaway loop.", turnIndex));
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.BUDGET_EXHAUSTED);
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
            } catch (Exception e) {
                log.error("Model call failed after retries", e);
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MODEL_CALL_FAILED);
                }
                return TurnResult.error("Model call failed: " + e.getMessage());
            }

            if (!response.hasToolCalls()) {
                turnMessages.add(Message.assistant(response.content(), turnIndex));
                log.debug("Turn {} completed without tool calls", i);
                triggerBackgroundReview(session, turnMessages);
                TurnExitReason reason = (response.content() == null || response.content().isBlank())
                    ? TurnExitReason.EMPTY_RESPONSE : TurnExitReason.COMPLETED;
                if (turnFinalizer != null) {
                    turnFinalizer.finalize(session.id(), turnMessages, true, reason);
                }
                return new TurnResult(turnMessages, true, null);
            }

            turnMessages.add(Message.assistantToolCalls(response.toolCalls(), turnIndex));

            int currentTurnIndex = turnIndex;
            List<ToolCall> toolCalls = response.toolCalls();
            List<Message> toolResults;

            if (toolCalls.size() == 1) {
                // Sequential path for single tool call
                toolResults = new ArrayList<>();
                ToolCall call = toolCalls.get(0);
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                    }
                    return new TurnResult(turnMessages, true, null);
                }
                // Check approval flow — use latch-based wait instead of busy-wait
                if (approvalQueue != null && approvalQueue.isPending(session.id())) {
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
                if (approvalQueue != null && approvalQueue.isDenied(session.id())) {
                    log.info("Tool {} denied for session {}, skipping", call.name(), session.id());
                    ToolResult deniedResult = ToolResult.fail("Tool execution denied by user approval");
                    toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(deniedResult), currentTurnIndex));
                    approvalQueue.clear(session.id());
                } else {
                    long toolStart = System.currentTimeMillis();
                    ToolResult result = toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState);
                    long duration = System.currentTimeMillis() - toolStart;
                    budget = iterationBudget.recordToolExecution(budget, call.name(), duration);
                    log.debug("Tool {} executed in {} ms: success={}, content length={}, error={}",
                        call.name(), duration, result.success(),
                        result.content() != null ? result.content().length() : 0, result.error());
                    toolResults.add(Message.toolResult(call.id(), toolResultFormatter.formatResult(result), currentTurnIndex));
                }
            } else {
                // Parallel path for multiple tool calls
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    turnMessages.add(Message.assistant("Turn cancelled by user.", turnIndex));
                    if (turnFinalizer != null) {
                        turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.INTERRUPTED);
                    }
                    return new TurnResult(turnMessages, true, null);
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
                Message lastToolResult = toolResults.get(toolResults.size() - 1);
                String enhancedContent = lastToolResult.content() + "\n\n[STEER NOTE] " + steerText;
                toolResults.set(toolResults.size() - 1,
                    Message.toolResult(lastToolResult.toolCallId(), enhancedContent, currentTurnIndex));
                log.info("Injected steer note for session {}", session.id());
            }
            turnMessages.addAll(toolResults);
            turnIndex++;
        }

        if (turnFinalizer != null) {
            turnFinalizer.finalize(session.id(), turnMessages, false, TurnExitReason.MAX_TURNS_REACHED);
        }
        return TurnResult.error("Reached max turns without completion");
    }

    /**
     * Calls modelClient.complete() with retry logic based on ErrorClassifier.
     * RETRYABLE errors use jittered backoff (500ms * 2^attempt + 0-250ms, cap 5s).
     * RATE_LIMIT errors use longer backoff (2s * 2^attempt, cap 30s).
     * CONTEXT_OVERFLOW errors trigger compression, then retry with compressed context.
     * PERMANENT/BILLING/CONTENT_POLICY errors fail immediately.
     */
    private ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools, Session session,
                                             ModelRequestOptions options) {
        int retryAttempts = properties.getError().getRetryAttempts();
        Exception lastException = null;
        int totalAttempts = 0;
        List<Message> currentContext = context;
        boolean compressionAttempted = false;

        for (int attempt = 0; attempt <= retryAttempts; attempt++) {
            totalAttempts++;
            try {
                return modelClient.complete(currentContext, tools, options);
            } catch (Exception e) {
                lastException = e;
                if (attempt >= retryAttempts) {
                    break;
                }
                ErrorClassifier.ErrorType errorType = errorClassifier.classify(e);

                // Context overflow: try compression before giving up
                if (errorType == ErrorClassifier.ErrorType.CONTEXT_OVERFLOW) {
                    if (compressionAttempted || contextCompressor == null) {
                        log.warn("Context overflow detected, compression already attempted or unavailable, failing: {}", e.getMessage());
                        break;
                    }
                    log.info("Context overflow detected, triggering compression");
                    try {
                        int targetChars = properties.getContext().getTargetTokens() * 4;
                        List<Message> compressed = contextCompressor.compress(currentContext, targetChars);
                        if (compressed.size() < currentContext.size()
                            || (compressed.size() == currentContext.size()
                                && compressed.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum()
                                < currentContext.stream().mapToInt(m -> m.content() != null ? m.content().length() : 0).sum())) {
                            currentContext = compressed;
                            compressionAttempted = true;
                            log.info("Context compressed from {} to {} messages, retrying model call",
                                context.size(), compressed.size());
                            // Don't consume a retry attempt for compression — retry immediately.
                            // The attempt-- below offsets the loop's attempt++ so that the
                            // compression retry effectively reuses the current attempt slot
                            // (net-zero change), giving compression a "free" retry that
                            // doesn't count against the retry budget.
                            attempt--;
                            continue;
                        } else {
                            log.warn("Context overflow detected, compression did not reduce context, failing: {}", e.getMessage());
                            break;
                        }
                    } catch (Exception ce) {
                        log.warn("Context compression failed: {}", ce.getMessage());
                        break;
                    }
                }

                if (errorType == ErrorClassifier.ErrorType.PERMANENT
                    || errorType == ErrorClassifier.ErrorType.BILLING
                    || errorType == ErrorClassifier.ErrorType.CONTENT_POLICY) {
                    log.warn("Model call failed with {} error, not retrying: {}", errorType, e.getMessage());
                    break;
                }
                // Calculate backoff delay — exponential: 2s, 4s, 8s, 16s, 32s (cap 60s)
                long retryDelayMs = properties.getError().getRetryDelayMs();
                long delayMs;
                if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                    delayMs = Math.min(retryDelayMs * (1L << attempt), 60_000L);
                } else {
                    long base = retryDelayMs * (1L << attempt);
                    long jitter = ThreadLocalRandom.current().nextLong(0, 500);
                    delayMs = Math.min(base + jitter, 60_000L);
                }
                log.warn("Model call failed (attempt {}/{}), classified as {}, retrying in {} ms: {}",
                    attempt + 1, retryAttempts + 1, errorType, delayMs, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.debug("Model call retry interrupted, stopping retries");
                    break;
                }
            }
        }
        throw new RuntimeException("Model call failed after " + totalAttempts + " attempt(s): "
            + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private void triggerBackgroundReview(Session session, List<Message> turnMessages) {
        try {
            if (backgroundReviewService != null) {
                backgroundReviewService.clearFlag(session.id());
                backgroundReviewService.reviewTurn(session.id(), turnMessages);
            }
        } catch (Exception e) {
            log.warn("Background review trigger failed: {}", e.getMessage());
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
}