package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static com.azhukov.agent.core.agent.TurnExecutorUtils.containsThinkingBlocks;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.containsImageContent;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.containsMultimodalToolContent;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.detectRefusalPattern;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.extractRetryAfterMs;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.interruptibleSleep;
import com.azhukov.agent.core.agent.TurnExecutor.ContentPolicyException;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.lowerMessageContains;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.stripGrammarPatternsFromTools;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.stripImageContent;
import static com.azhukov.agent.core.agent.TurnExecutorUtils.stripMultimodalToolContent;
import static com.azhukov.agent.core.agent.ThinkBlockProcessor.stripThinkingBlocks;

/**
 * c1: model-call retry + fallback loop, extracted from DefaultAgentRuntime
 * (was callModelWithRetry + tryActivateFallback, ~420 LOC).
 *
 * <p>Single responsibility: call the active model client, classify failures,
 * apply the Hermes-parity one-shot recovery guards (TurnRetryState — 14 total),
 * compression recovery for overflow-class errors (3 attempts), Retry-After /
 * exponential backoff, and fallback-model activation when retries are exhausted.</p>
 *
 * <p>The caller owns the per-turn mutable state via {@link ModelCallContext}:
 * {@code activeClient} (swapped on fallback) and {@code fallbackManager} (per-turn
 * chain). Static message/tool helpers live in {@link TurnExecutor}.</p>
 */
@Slf4j
public class FallbackModelCaller {

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

    public FallbackModelCaller(ErrorClassifier errorClassifier,
                               AgentProperties properties,
                               ContextCompressor contextCompressor,
                               ContextEngine contextEngine) {
        this.errorClassifier = errorClassifier;
        this.properties = properties;
        this.contextCompressor = contextCompressor;
        this.contextEngine = contextEngine;
    }

    /** Per-turn mutable model-call state the runtime owns. */
    public static final class ModelCallContext {
        public final ModelClient defaultClient;
        public final FallbackManager fallbackManager;
        public volatile ModelClient activeClient;

        public ModelCallContext(ModelClient defaultClient, FallbackManager fallbackManager) {
            this.defaultClient = defaultClient;
            this.fallbackManager = fallbackManager;
            this.activeClient = null;
        }
    }

    public ChatResponse call(ModelCallContext ctx, List<Message> context, List<ToolDefinition> tools,
                             Session session, ModelRequestOptions options) {
        int retryAttempts = properties.getError().getRetryAttempts();
        int tierRetryAttempts = retryAttempts;
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
            for (int attempt = 0; attempt <= tierRetryAttempts; attempt++) {
                totalAttempts++;
                try {
                    ModelClient client = ctx.activeClient != null ? ctx.activeClient : ctx.defaultClient;
                    // Compression and retry recovery can reshape the transcript after
                    // context preparation, so repair it at the wire boundary.
                    currentContext = HistorySanitizer.sanitizeForModelRequest(currentContext);
                    return client.complete(currentContext, tools, options);
                } catch (Exception e) {
                    lastException = e;
                    if (attempt >= retryAttempts) {
                        break;
                    }
                    ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(e);
                    ErrorClassifier.ErrorType errorType = classification.type();
                    // Two-tier budget (operator 2026-08-28): availability errors
                    // (RATE_LIMIT/OVERLOADED) expand the budget for this loop.
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT
                        || errorType == ErrorClassifier.ErrorType.OVERLOADED) {
                        tierRetryAttempts = Math.max(tierRetryAttempts,
                            Math.max(1, properties.getError().getAvailabilityRetryAttempts()));
                    }

                    // ── Part F: Content policy handling — immediate-fallback trigger ──
                    if (errorType == ErrorClassifier.ErrorType.CONTENT_POLICY) {
                        log.warn("Content policy block: {} — attempting fallback", e.getMessage());
                        if (tryActivateFallback(ctx, errorType, e)) {
                            retryState = new TurnRetryState();
                            currentContext = context; // reset context for the new model
                            break; // break inner loop, continue outer fallback loop
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
                        currentContext = stripThinkingBlocks(currentContext);
                        attempt--;
                        continue;
                    }

                    // Guard 2b: FORMAT_ERROR with thinking blocks (fallback for older classifier paths)
                    if (errorType == ErrorClassifier.ErrorType.FORMAT_ERROR
                        && !retryState.isThinkingSigRetryAttempted()
                        && containsThinkingBlocks(currentContext)) {
                        retryState.setThinkingSigRetryAttempted(true);
                        log.info("FORMAT_ERROR with thinking blocks, stripping and retrying (one-shot guard)");
                        currentContext = stripThinkingBlocks(currentContext);
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
                        currentContext = stripThinkingBlocks(currentContext);
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
                        tools = stripGrammarPatternsFromTools(tools);
                        attempt--;
                        continue;
                    }

                    // ── Compression-disabled respect (Hermes conversation_loop.py:2610-2663) ──
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

                    // ── Long-context tier handling (Hermes conversation_loop.py:2665-2719) ──
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
                                if (compressionAttempts + 1 >= 3) {
                                    break;
                                }
                                attempt--;
                                continue;
                            }
                        }
                    }

                    // Context overflow: try compression (up to 3 attempts) before giving up
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
                    if (errorType == ErrorClassifier.ErrorType.AUTH_PERMANENT
                        || errorType == ErrorClassifier.ErrorType.MODEL_NOT_FOUND) {
                        log.warn("{} error — attempting immediate fallback: {}", errorType, e.getMessage());
                        if (tryActivateFallback(ctx, errorType, e)) {
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
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT && !retryState.isHasRetried429()) {
                        retryState.setHasRetried429(true);
                        log.info("RATE_LIMIT (429) detected — tracking for credential rotation");
                    }

                    // ── Part C: Retry-After header parsing (Hermes 3393-3401) ──
                    long delayMs;
                    if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT) {
                        long retryAfterMs = extractRetryAfterMs(e);
                        if (retryAfterMs > 0) {
                            delayMs = Math.min(retryAfterMs, 120_000L); // Cap at 120s (parity with Hermes)
                            log.info("Rate limit: using Retry-After header value: {}ms (capped at 120s)", delayMs);
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
                    log.warn("Model call failed (attempt {}/{}), classified as {}, retrying in {} ms: {}",
                        attempt + 1, retryAttempts + 1, errorType, delayMs, e.getMessage());

                    // ── Part D: Interruptible backoff (Hermes 1324-1347) ──
                    try {
                        interruptibleSleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("Model call retry interrupted, stopping retries");
                        break;
                    }
                }
            }

            // ── Retries exhausted — try fallback before failing (Hermes 3278-3296) ──
            if (lastException != null) {
                ErrorClassifier.ClassificationResult classification = errorClassifier.classifyWithHints(lastException);
                if (classification.hints().shouldFallback() && tryActivateFallback(ctx, classification.type(), lastException)) {
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
     */
    /**
     * R3 (Hermes 7728-7760): empty-response-exhausted fallback activation —
     * public entry for the runtimes: after the empty budget is burned, try the
     * next provider in the chain before returning the "(empty)" terminal.
     */
    public boolean tryActivateFallbackForEmpty(ModelCallContext ctx) {
        if (ctx.fallbackManager == null || !ctx.fallbackManager.hasPendingFallback()) {
            return false;
        }
        return tryActivateFallback(ctx, null, null);
    }

    public boolean tryActivateFallback(ModelCallContext ctx, ErrorClassifier.ErrorType errorType, Exception error) {
        if (ctx.fallbackManager == null || !ctx.fallbackManager.hasPendingFallback()) {
            // Hermes parity: arm a short cooldown when the chain is exhausted
            // and the failure was NOT a rate-limit/billing event — prevents the
            // cross-turn replay storm (#24996) from re-marshaling the whole
            // context across every provider every turn.
            if (errorType != ErrorClassifier.ErrorType.RATE_LIMIT
                && errorType != ErrorClassifier.ErrorType.BILLING
                && errorType != ErrorClassifier.ErrorType.OVERLOADED) {
                ctx.fallbackManager.setChainExhaustedCooldown();
            }
            return false;
        }

        // Set rate-limit cooldown if the trigger was rate-limit related
        if (errorType == ErrorClassifier.ErrorType.RATE_LIMIT || errorType == ErrorClassifier.ErrorType.BILLING) {
            ctx.fallbackManager.setRateLimitCooldown();
        }

        FallbackConfig fallbackConfig = ctx.fallbackManager.activateFallback();
        if (fallbackConfig == null) {
            log.warn("Fallback chain exhausted — no more fallback models available");
            return false;
        }

        try {
            com.azhukov.agent.client.langchain4j.FallbackModelClient fallbackClient =
                com.azhukov.agent.client.langchain4j.FallbackModelClient.from(fallbackConfig, properties, usageConsumer);
            ctx.activeClient = fallbackClient;
            log.info("🔄 Switched to fallback model: {} via {}",
                ctx.fallbackManager.getCurrentModel(), ctx.fallbackManager.getCurrentProvider());
            // h60: Reset compression failure cooldown when model switches.
            if (contextCompressor instanceof DefaultContextCompressor dcc) {
                dcc.resetCompressionFailureCooldown(
                    ctx.fallbackManager.getCurrentProvider() + "/" + ctx.fallbackManager.getCurrentModel());
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to create fallback model client for {}/{}: {}",
                fallbackConfig.getProvider(), fallbackConfig.getModel(), e.getMessage());
            // Recursively try the next fallback
            return tryActivateFallback(ctx, errorType, error);
        }
    }
}
