package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
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
 *   <li>{@link #callModelWithRetry} — model call with retry, fallback, and
 *       one-shot recovery guards (parity with Hermes
 *       {@code _retry_with_recoveries})</li>
 *   <li>{@link #executeToolsInParallel} — parallel tool execution via a shared
 *       virtual-thread executor</li>
 *   <li>{@link #executeToolBatch} — full sequential-or-parallel tool batch
 *       execution with the parallel-safety gate, approval flow, and
 *       memory-nudge counter resets</li>
 *   <li>{@link #checkProactiveCompression} — post-tool-batch context
 *       compression check</li>
 *   <li>{@link #classifyForRetry} — error classification + backoff calculation
 *       shared by the streaming path (which cannot reuse the synchronous
 *       retry loop directly)</li>
 *   <li>Static helpers: {@link #interruptibleSleep}, {@link #detectRefusalPattern},
 *       {@link #estimateResponseTokens(ChatResponse)},
 *       {@link #estimateResponseTokens(String, List)}, plus think/image/multimodal
 *       content helpers</li>
 * </ul>
 * <p>
 * {@code DefaultAgentRuntime} delegates model calling + tool execution to this
 * class. {@code AgentStreamingService} delegates the retry classification and
 * backoff calculation (the non-SSE-specific part of "model call with retry")
 * while keeping its own SSE streaming wrapper.
 *
 * <h2>Fallback context</h2>
 * The rich retry loop depends on a per-turn {@link FallbackManager} and an
 * {@code activeModelClient} field that the caller owns. Callers pass a
 * {@link FallbackContext} snapshot so {@code TurnExecutor} can swap models
 * mid-retry without owning the per-turn mutable state itself.
 */
@Slf4j
@Component
public class TurnExecutor {

    private final ErrorClassifier errorClassifier;
    private final AgentProperties properties;
    private final ContextCompressor contextCompressor;
    private final ContextEngine contextEngine;

    /** Optional usage sink injected by the runtime (Hermes parity: fallback tokens are billed). */
    private volatile java.util.function.Consumer<LangChain4jModelClient.Usage> usageConsumer;

    /** Runtime hook: register the turn usage sink so fallback completions are billed. */
    public void setUsageConsumer(java.util.function.Consumer<LangChain4jModelClient.Usage> usageConsumer) {
        this.usageConsumer = usageConsumer;
    }
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

    public TurnExecutor(ErrorClassifier errorClassifier,
                         AgentProperties properties,
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
        this.errorClassifier = errorClassifier;
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
     * @return the trimmed summary, or null when the call failed or was blank
     */
    public String requestBudgetExhaustionSummary(ModelClient activeClient, Session session,
                                                  List<Message> turnMessages,
                                                  com.azhukov.agent.core.client.ModelRequestOptions options) {
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

    // ──────────────────────────────────────────────────────────────────
    //  Model call with retry (shared by both runtimes)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable per-turn fallback state passed in by the caller.
     * <p>
     * {@code DefaultAgentRuntime} owns the {@link FallbackManager} and the
     * {@code activeModelClient} field because they are initialised per turn
     * from session/config data. This holder lets {@code TurnExecutor} swap
     * the active model mid-retry without owning that per-turn state.
     */
    public static final class FallbackContext {
        private FallbackManager fallbackManager;
        private ModelClient activeModelClient;
        private final ModelClient primaryModelClient;

        public FallbackContext(ModelClient primaryModelClient) {
            this.primaryModelClient = primaryModelClient;
            this.activeModelClient = primaryModelClient;
        }

        public FallbackManager getFallbackManager() { return fallbackManager; }
        public void setFallbackManager(FallbackManager fm) { this.fallbackManager = fm; }
        public ModelClient getActiveModelClient() { return activeModelClient; }
        public void setActiveModelClient(ModelClient c) { this.activeModelClient = c; }
        public ModelClient getPrimaryModelClient() { return primaryModelClient; }
    }

    /**
     * Calls the active model client with full retry logic: error classification,
     * one-shot recovery guards (14 total, parity with Hermes), fallback chain
     * activation, and interruptible backoff.
     * <p>
     * This is the canonical "model call with retry" extracted from
     * {@code DefaultAgentRuntime.callModelWithRetry}. Both runtimes use it:
     * {@code DefaultAgentRuntime} directly, and {@code AgentStreamingService}
     * uses {@link #classifyForRetry} for the parts that are reusable in the
     * streaming path.
     *
     * @param context          the prepared message context
     * @param tools            the tool definitions available to the model
     * @param session          the current session (for interrupt checks)
     * @param options          model request options
     * @param fallbackCtx      per-turn fallback state (may be null if no fallback)
     * @return the model response
     * @throws ContentPolicyException if a content policy block has no fallback
     * @throws RuntimeException        if all retries and fallbacks are exhausted
     */
    public ChatResponse callModelWithRetry(List<Message> context, List<ToolDefinition> tools,
                                            Session session, ModelRequestOptions options,
                                            FallbackContext fallbackCtx) {
        int retryAttempts = properties.getError().getRetryAttempts();
        Exception lastException = null;
        int totalAttempts = 0;
        List<Message> currentContext = context;
        List<ToolDefinition> currentTools = tools;
        TurnRetryState retryState = new TurnRetryState();

        for (;;) {
            int tierRetryAttempts = retryAttempts;
            for (int attempt = 0; attempt <= tierRetryAttempts; attempt++) {
                totalAttempts++;
                try {
                    ModelClient client = fallbackCtx != null && fallbackCtx.getActiveModelClient() != null
                        ? fallbackCtx.getActiveModelClient()
                        : (fallbackCtx != null ? fallbackCtx.getPrimaryModelClient() : null);
                    currentContext = HistorySanitizer.sanitizeForModelRequest(currentContext);
                    return client.complete(currentContext, currentTools, options);
                } catch (Exception e) {
                    lastException = e;
                    if (attempt >= retryAttempts) {
                        break;
                    }
                    ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(e);
                    ErrorClassifier.ErrorType errorType = classification.type();
                    // Two-tier budget (operator 2026-08-28): availability errors
                    // (RATE_LIMIT/OVERLOADED) expand the budget for this loop.
                    tierRetryAttempts = Math.max(tierRetryAttempts, retryBudgetFor(errorType));

                    // ── Part F: Content policy handling ──
                    if (errorType == ErrorClassifier.ErrorType.CONTENT_POLICY) {
                        log.warn("Content policy block: {} — attempting fallback", e.getMessage());
                        if (tryActivateFallback(errorType, e, fallbackCtx)) {
                            retryState = new TurnRetryState();
                            currentContext = context;
                            break;
                        }
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

                    // Guard 2b: FORMAT_ERROR with thinking blocks
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
                        attempt--;
                        continue;
                    }

                    // Guard 7: LLAMA_CPP_GRAMMAR — strip tool schema patterns
                    if (errorType == ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR
                        && !retryState.isLlamaCppGrammarRetryAttempted()) {
                        retryState.setLlamaCppGrammarRetryAttempted(true);
                        log.info("LLAMA_CPP_GRAMMAR error, stripping pattern/format from tools and retrying (one-shot guard)");
                        currentTools = stripGrammarPatternsFromTools(currentTools);
                        attempt--;
                        continue;
                    }

                    // ── Compression-disabled respect ──
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

                    // ── Long-context tier handling ──
                    if (errorType == ErrorClassifier.ErrorType.LONG_CONTEXT_TIER) {
                        int reducedContext = 200_000;
                        if (contextCompressor instanceof DefaultContextCompressor dcc) {
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
                                // Hermes parity: 600s summary-failure cooldown so a wedged
                                // summarizer doesn't burn paid retries (context_compressor.py
                                // _SUMMARY_FAILURE_COOLDOWN_SECONDS). Transient failures use
                                // the 60/300/900s ladder instead.
                                applyCompressionFailureCooldown(contextCompressor, compressionAttempts + 1, ce);
                                if (compressionAttempts + 1 >= 3) {
                                    break;
                                }
                                attempt--;
                                continue;
                            }
                        }
                    }

                    // Context overflow: try compression (up to 3 attempts)
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
                            // Hermes parity: failure cooldown (600s) / transient ladder (60/300/900s).
                            applyCompressionFailureCooldown(contextCompressor, compressionAttempts + 1, ce);
                            if (compressionAttempts + 1 >= 3) {
                                break;
                            }
                            attempt--;
                            continue;
                        }
                    }

                    // Payload too large: try compression (up to 3 attempts)
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
                            // Hermes parity: failure cooldown (600s) / transient ladder (60/300/900s).
                            applyCompressionFailureCooldown(contextCompressor, compressionAttempts + 1, ce);
                            if (compressionAttempts + 1 >= 3) {
                                break;
                            }
                            attempt--;
                            continue;
                        }
                    }

                    // ── Immediate-fallback errors (no retry, try fallback immediately) ──
                    if (errorType == ErrorClassifier.ErrorType.AUTH_PERMANENT
                        || errorType == ErrorClassifier.ErrorType.MODEL_NOT_FOUND) {
                        log.warn("{} error — attempting immediate fallback: {}", errorType, e.getMessage());
                        if (tryActivateFallback(errorType, e, fallbackCtx)) {
                            retryState = new TurnRetryState();
                            currentContext = context;
                            break;
                        }
                        log.warn("{} error, no fallback available, failing: {}", errorType, e.getMessage());
                        break;
                    }

                    // ── Permanent errors that don't trigger fallback ──
                    if (errorType == ErrorClassifier.ErrorType.PERMANENT
                        || errorType == ErrorClassifier.ErrorType.BILLING
                        || errorType == ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED
                        || errorType == ErrorClassifier.ErrorType.SSL_CERT_VERIFICATION) {
                        log.warn("Model call failed with {} error, not retrying: {}", errorType, e.getMessage());
                        break;
                    }

                    // ── Credential rotation for RATE_LIMIT ──
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT && !retryState.isHasRetried429()) {
                        retryState.setHasRetried429(true);
                        log.info("RATE_LIMIT (429) detected — tracking for credential rotation");
                    }

                    // ── Part C: Retry-After header parsing + backoff calculation ──
                    long delayMs = computeBackoffMs(errorType, attempt, e);

                    log.warn("Model call failed (attempt {}/{}), classified as {}, retrying in {} ms: {}",
                        attempt + 1, retryAttempts + 1, errorType, delayMs, e.getMessage());

                    // ── Part D: Interruptible backoff ──
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
            if (lastException != null) {
                ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(lastException);
                if (classification.hints().shouldFallback() && tryActivateFallback(classification.type(), lastException, fallbackCtx)) {
                    retryState = new TurnRetryState();
                    currentContext = context;
                    totalAttempts = 0;
                    continue;
                }
            }

            break;
        }

        throw new RuntimeException("Model call failed after " + totalAttempts + " attempt(s): "
            + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    /**
     * Result of classifying a streaming error for retry purposes.
     * <p>
     * Carries the classified error type and the computed backoff delay so the
     * streaming path can decide whether to retry and how long to wait without
     * duplicating the classification + backoff logic.
     */
    public record RetryClassification(ErrorClassifier.ErrorType errorType, long backoffMs) {}

    /**
     * Classify a streaming error and compute the backoff delay, without
     * performing the retry itself (the streaming path owns its own loop).
     * <p>
     * This is the shared, SSE-agnostic half of "model call with retry" that
     * {@code AgentStreamingService} delegates to so it doesn't duplicate the
     * error-classification + backoff calculation logic.
     *
     * @param error   the exception from the streaming call
     * @param attempt zero-based attempt index (for exponential backoff)
     * @return classification result with error type and a computed backoff delay in ms
     */
    public RetryClassification classifyForRetry(Exception error, int attempt) {
        ErrorClassifier.ErrorType errorType = errorClassifier.classify(error);
        long delayMs = computeBackoffMs(errorType, attempt, error);
        return new RetryClassification(errorType, delayMs);
    }

    /**
     * Tier-aware retry budget for the given classified error type
     * (operator decision 2026-08-28): plain errors → error.retry-attempts (3),
     * availability errors (RATE_LIMIT/OVERLOADED) → error.availability-retry-attempts (20).
     */
    public int retryBudgetFor(ErrorClassifier.ErrorType errorType) {
        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT
            || errorType == ErrorClassifier.ErrorType.OVERLOADED) {
            return Math.max(1, properties.getError().getAvailabilityRetryAttempts());
        }
        return Math.max(1, properties.getError().getRetryAttempts());
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

    /** One executed tool call, for post-batch budget recording by the caller. */
    public record ToolExecutionRecord(String toolName, long durationMs, boolean refunded) {}

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
            && ToolParallelSafety.shouldParallelize(toolCalls, registeredToolNames);

        if (!shouldParallel) {
            // Sequential path
            List<Message> toolResults = new ArrayList<>();
            List<ToolExecutionRecord> executions = new ArrayList<>();
            for (ToolCall call : toolCalls) {
                if (interruptToken != null && interruptToken.isCancelled(session.id())) {
                    log.info("Turn cancelled by interrupt for session {}", session.id());
                    return new ToolBatchResult(toolResults, true, executions);
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
                boolean isSubagentChild = !skipApproval && session.getMetadata("delegation_parent_session") != null;
                if (isSubagentChild) {
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
                boolean flagRequiresApproval = !skipApproval && toolGuardrails != null
                    && toolGuardrails.requiresApproval(call);
                if (flagRequiresApproval && approvalQueue != null
                        && approvalQueue.getPending(session.id()) == null) {
                    requestApproval(session.id(), call);
                }
                boolean approvalRequired = !skipApproval && approvalQueue != null
                    && (approvalQueue.isPending(session.id()) || flagRequiresApproval);
                if (approvalRequired) {
                    log.info("Tool {} requires approval for session {}, waiting...", call.name(), session.id());
                    long approvalTimeoutMs = java.time.Duration.ofMinutes(5).toMillis();
                    boolean decided = approvalQueue.awaitDecision(session.id(), approvalTimeoutMs);
                    if (!decided) {
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
                if (approvalRequired && !approvalQueue.isApproved(session.id())) {
                    boolean denied = approvalQueue.isDenied(session.id());
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
                    ToolResult result = toolExecutionService.execute(call.name(), call.id(), call.arguments(), null, session, turnState);
                    long duration = System.currentTimeMillis() - toolStart;
                    String formatted = toolResultFormatter.formatResult(result);
                    toolResults.add(Message.toolResult(call.pairingId(), formatted, currentTurnIndex));
                    // Hermes parity (conversation_loop.py:7277-7280): execute_code
                    // executions are refunded — programmatic calls are cheap RPCs
                    // and must not starve the per-turn budget. Previously this
                    // refund existed ONLY on the streaming path.
                    boolean refunded = allExecuteCode && "execute_code".equals(call.name());
                    executions.add(new ToolExecutionRecord(call.name(), duration, refunded));
                    if (events != null) events.onToolResult(call, result, formatted);
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
                    allExecuteCode && "execute_code".equals(call.name())));
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
        log.info("Injected steer note for session {}", sessionId);
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
     * CONTEXT_OVERFLOW handled in {@link #callModelWithRetry}.
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

    /**
     * Attempt to activate the next fallback model in the chain.
     *
     * @param errorType   the error type that triggered the fallback
     * @param error       the exception that caused the fallback
     * @param fallbackCtx the per-turn fallback context
     * @return true if fallback was activated, false if chain is exhausted
     */
    private boolean tryActivateFallback(ErrorClassifier.ErrorType errorType, Exception error,
                                         FallbackContext fallbackCtx) {
        if (fallbackCtx == null || fallbackCtx.getFallbackManager() == null
            || !fallbackCtx.getFallbackManager().hasPendingFallback()) {
            return false;
        }
        FallbackManager fm = fallbackCtx.getFallbackManager();

        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT || errorType == ErrorClassifier.ErrorType.BILLING) {
            fm.setRateLimitCooldown();
        }

        FallbackConfig fallbackConfig = fm.activateFallback();
        if (fallbackConfig == null) {
            log.warn("Fallback chain exhausted — no more fallback models available");
            return false;
        }

        try {
            com.azhukov.agent.client.langchain4j.FallbackModelClient fallbackClient =
                com.azhukov.agent.client.langchain4j.FallbackModelClient.from(fallbackConfig, properties, usageConsumer);
            fallbackCtx.setActiveModelClient(fallbackClient);
            log.info("🔄 Switched to fallback model: {} via {}",
                fm.getCurrentModel(), fm.getCurrentProvider());
            if (contextCompressor instanceof DefaultContextCompressor dcc) {
                dcc.resetCompressionFailureCooldown(
                    fm.getCurrentProvider() + "/" + fm.getCurrentModel());
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to create fallback model client for {}/{}: {}",
                fallbackConfig.getProvider(), fallbackConfig.getModel(), e.getMessage());
            return tryActivateFallback(errorType, error, fallbackCtx);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Compression failure cooldown (Hermes parity: h60 wiring)
    // ──────────────────────────────────────────────────────────────────

    /** Hermes _SUMMARY_FAILURE_COOLDOWN_SECONDS: 600s cooldown after a hard summary failure. */
    private static final long COMPRESSION_FAILURE_COOLDOWN_MS = 600_000L;

    /** Hermes _TIMEOUT_COOLDOWN_LADDER (60, 300, 900)s for transient/timeout failures. */
    private static final long[] TRANSIENT_COOLDOWN_LADDER_MS = {60_000L, 300_000L, 900_000L};

    /**
     * Applies the Hermes compression-failure cooldown after a failed compression attempt
     * (context_compressor.py:4763, 4884-4901): transient failures get an escalating ladder —
     * timeouts 60/300/900s, JSON-decode/stream-closed 30s, other transient 60s — and hard
     * (non-transient) summary failures get the flat 600s cooldown. No-op for compressor
     * implementations without cooldown support.
     */
    private void applyCompressionFailureCooldown(ContextCompressor compressor, int attempt, Exception cause) {
        if (!(compressor instanceof DefaultContextCompressor dcc)) {
            return;
        }
        String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase(Locale.ROOT) : "";
        long cooldownMs;
        if (msg.contains("timeout") || msg.contains("timed out")) {
            // Timeout ladder: 60/300/900s by consecutive failure count (attempt 1-based).
            int idx = Math.min(attempt - 1, TRANSIENT_COOLDOWN_LADDER_MS.length - 1);
            cooldownMs = TRANSIENT_COOLDOWN_LADDER_MS[idx];
        } else if (msg.contains("json") || msg.contains("stream") && msg.contains("closed")) {
            cooldownMs = 30_000L;
        } else if (TurnExecutorUtils.isTransient(msg)) {
            cooldownMs = 60_000L;
        } else {
            cooldownMs = COMPRESSION_FAILURE_COOLDOWN_MS;
        }
        dcc.setCompressionFailureCooldown(cooldownMs);
        log.info("Compression failure cooldown set: {}s (attempt {}, {})",
            cooldownMs / 1000, attempt, TurnExecutorUtils.classifyForLog(msg));
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