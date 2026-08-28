package com.azhukov.agent.core.context;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Package-private policy/threshold logic extracted from {@link DefaultContextCompressor}.
 * <p>
 * Owns:
 * <ul>
 *   <li>Dynamic compression threshold calculation ({@link #recalculateThreshold}, {@link #getCompressionThresholdChars})</li>
 *   <li>Proactive & reactive compression decision logic ({@link #shouldCompressProactive}, {@link #shouldCompress})</li>
 *   <li>Anti-thrashing counters ({@link #recordCompressionSavings}, {@link #resetAntiThrashing}, {@link #getConsecutiveLowSavings}, {@link #getLastCompressionSavingsPct})</li>
 *   <li>Compression failure cooldown ({@link #resetCompressionFailureCooldown}, {@link #isCompressionFailureCooldownActive}, {@link #setCompressionFailureCooldown})</li>
 *   <li>Summary budget computation ({@link #computeSummaryBudget})</li>
 *   <li>Global compression count (used for {@code protectFirstN} decay)</li>
 * </ul>
 * {@link DefaultContextCompressor} delegates all of the above to this class.
 */
@Slf4j
class CompressionPolicy {

    // ── Constants (mirrors of Hermes context_compressor.py) ──

    /**
     * P7 parity (context_compressor.py:3104): default threshold fraction 0.50 —
     * same as {@code agent.context.threshold-percent} default. Per-model overrides
     * and the small-context 75% floor apply on top of this.
     */
    static final double COMPRESSION_THRESHOLD_FRACTION = 0.50;

    /**
     * Minimum context length required to run the agent (mirrors Hermes MINIMUM_CONTEXT_LENGTH = 64_000).
     * <p>
     * This floor prevents premature compression on large-context models: a 200K-context model
     * at 50% threshold would compress at 100K tokens, which is correct — but the 64K floor
     * prevents even larger models (1M) from compressing at a too-low absolute token count.
     */
    static final int MINIMUM_CONTEXT_LENGTH = 64_000;

    /**
     * Proactive compression threshold fraction — 50% of the context window (mirrors Hermes threshold_percent default).
     * Used by {@link #shouldCompressProactive} to check whether to compress after tool batches.
     */
    static final double PROACTIVE_THRESHOLD_FRACTION = 0.50;

    /**
     * Anti-thrashing: minimum savings percentage for a compression to be considered "effective".
     * If compression saves less than this percentage, it's counted as a low-savings compression.
     * Mirrors Hermes: {@code savings_pct < 10} → increment _ineffective_compression_count.
     */
    static final double LOW_SAVINGS_THRESHOLD_PCT = 10.0;

    /**
     * Anti-thrashing: maximum consecutive low-savings compressions before shouldCompress returns false.
     * Mirrors Hermes: {@code if self._ineffective_compression_count >= 2: return False}.
     */
    static final int MAX_CONSECUTIVE_LOW_SAVINGS = 2;

    /**
     * Maximum compression attempts on context overflow before giving up.
     * Mirrors Hermes: {@code max_compression_attempts = 3}.
     */
    static final int MAX_COMPRESSION_ATTEMPTS = 3;

    /** Chars per token rough estimate. */
    static final int CHARS_PER_TOKEN = 4;

    /** Minimum summary token budget. */
    static final int MIN_SUMMARY_TOKENS = 2_000;
    /** Proportion of compressed content to allocate for summary. */
    static final double SUMMARY_RATIO = 0.20;
    /** Absolute ceiling for summary tokens. Hermes parity: _SUMMARY_TOKENS_CEILING = 10_000. */
    static final int SUMMARY_TOKENS_CEILING = 10_000;

    // ── Hermes parity: per-model threshold overrides and small-context floor ──

    /** Hermes parity: _SMALL_CTX_WINDOW_LIMIT — models with context below this get a raised threshold. */
    static final int SMALL_CTX_WINDOW_LIMIT = 512_000;

    /** Hermes parity: _SMALL_CTX_THRESHOLD_PERCENT — floor for small-context models (raise-only). */
    static final double SMALL_CTX_THRESHOLD_PERCENT = 0.75;

    /** Hermes parity: _MIN_CTX_TRIGGER_RATIO — reachable trigger when the 64K floor fills the input budget. */
    static final double MIN_CTX_TRIGGER_RATIO = 0.85;

    /**
     * Hermes parity (context_compressor.py:7586, _FEASIBILITY_SKIP_MIDDLE_FRACTION = 0.10):
     * once ONE ineffective compression happened, a pre-LLM feasibility check
     * skips further summary calls when the middle window holds under 10% of
     * the threshold — there is nothing compressible, calling the summarizer
     * would only burn tokens. Skipped for force (manual /compress).
     */
    static final double FEASIBILITY_SKIP_MIDDLE_FRACTION = 0.10;

    /**
     * Hermes parity: resolve_model_threshold — resolve the effective compression threshold
     * for a given model, supporting per-model substring overrides (longest match wins).
     * Mirrors context_compressor.py resolve_model_threshold().
     *
     * @param model the model name (e.g. "glm-5.2-1M")
     * @param modelThresholds map of substring→fraction overrides, or null/empty for default
     * @param defaultThreshold the default threshold fraction
     * @return the effective threshold fraction
     */
    static double resolveModelThreshold(String model, java.util.Map<String, Double> modelThresholds, double defaultThreshold) {
        if (modelThresholds == null || modelThresholds.isEmpty() || model == null || model.isBlank()) {
            return defaultThreshold;
        }
        String bestKey = "";
        for (String key : modelThresholds.keySet()) {
            if (model.contains(key) && key.length() > bestKey.length()) {
                bestKey = key;
            }
        }
        if (!bestKey.isEmpty()) {
            return modelThresholds.get(bestKey);
        }
        return defaultThreshold;
    }

    // ── State fields ──

    /**
     * P2-51: Dynamic compression threshold in chars, recalculated when the model switches.
     * 0 means "not set" — fall back to config-based targetTokens at call sites.
     */
    private volatile int compressionThresholdChars = 0;

    /**
     * Anti-thrashing: consecutive low-savings compression counter.
     * <p>
     * Mirrors Hermes {@code _ineffective_compression_count}. After each compression, if savings
     * are less than {@link #LOW_SAVINGS_THRESHOLD_PCT} (10%), this counter increments. If it
     * reaches {@link #MAX_CONSECUTIVE_LOW_SAVINGS} (2), {@link #shouldCompress} returns false
     * to skip compression and avoid thrashing.
     * <p>
     * Reset to 0 when a compression saves more than 10%.
     */
    private volatile int consecutiveLowSavings = 0;

    /**
     * Anti-thrashing: last compression savings percentage (0-100).
     * Mirrors Hermes {@code _last_compression_savings_pct}.
     */
    private volatile double lastCompressionSavingsPct = 100.0;

    // h60: Compression failure cooldown — tracks the model/provider for which the cooldown was set.
    // When the model switches, the cooldown is reset so the new model gets a fresh start.
    private volatile String compressionCooldownModelKey;
    private volatile long compressionFailureCooldownUntil;

    /** Global compression count — used for protectFirstN decay (mirrors Hermes compression_count). */
    private final AtomicInteger globalCompressionCount = new AtomicInteger(0);

    // ── Threshold recalculation ──

    /**
     * P2-51: Recalculate the compression threshold when the model switches.
     * <p>
     * Different models have different context window sizes. When the user switches
     * models mid-session, the compression threshold must be updated to reflect
     * the new model's context window. The threshold is computed as:
     * <pre>
     *   thresholdTokens = max((int)(newContextWindowSize * 0.75), MINIMUM_CONTEXT_LENGTH)
     *   thresholdChars  = thresholdTokens * CHARS_PER_TOKEN
     * </pre>
     * The {@link #MINIMUM_CONTEXT_LENGTH} floor prevents premature compression on
     * large-context models (e.g., a 200K model at 75% compresses at 150K, which is
     * correct, but a 1M model shouldn't compress at 750K — the 64K floor ensures
     * reasonable behavior).
     *
     * @param newContextWindowSize the new model's context window size in tokens
     */
    void recalculateThreshold(int newContextWindowSize) {
        recalculateThreshold(newContextWindowSize, null, null);
    }

    /**
     * Hermes parity: recalculate threshold with per-model overrides and small-context floor.
     * Mirrors context_compressor.py __init__ threshold resolution:
     * 1. Start with {@link #COMPRESSION_THRESHOLD_FRACTION} (0.75).
     * 2. Apply per-model substring overrides (longest match wins) via {@link #resolveModelThreshold}.
     * 3. Apply small-context floor: if context window < {@link #SMALL_CTX_WINDOW_LIMIT},
     *    raise the threshold to at least {@link #SMALL_CTX_THRESHOLD_PERCENT} (raise-only).
     * 4. Floor the result at {@link #MINIMUM_CONTEXT_LENGTH} tokens.
     *
     * @param newContextWindowSize the new model's context window size in tokens
     * @param model the model name (for per-model overrides), or null
     * @param modelThresholds per-model threshold overrides (substring→fraction), or null
     */
    void recalculateThreshold(int newContextWindowSize, String model, java.util.Map<String, Double> modelThresholds) {
        recalculateThreshold(newContextWindowSize, model, modelThresholds, 0);
    }

    /**
     * Hermes parity: calculate the trigger against the effective input budget,
     * reserving output tokens so compaction runs before a provider rejects a full request.
     */
    void recalculateThreshold(int newContextWindowSize, String model,
                              java.util.Map<String, Double> modelThresholds, int maxOutputTokens) {
        if (newContextWindowSize <= 0) {
            log.debug("recalculateThreshold: ignoring non-positive context window size {}", newContextWindowSize);
            return;
        }
        // h60: Reset compression failure cooldown when the model/context switches.
        resetCompressionFailureCooldown("ctx-" + newContextWindowSize);

        double thresholdFraction = resolveModelThreshold(model, modelThresholds, COMPRESSION_THRESHOLD_FRACTION);
        if (newContextWindowSize < SMALL_CTX_WINDOW_LIMIT && thresholdFraction < SMALL_CTX_THRESHOLD_PERCENT) {
            thresholdFraction = SMALL_CTX_THRESHOLD_PERCENT;
        }

        // Hermes _compute_threshold_tokens: reserve output capacity from the same
        // context window. A nonsensical reservation falls back to the full window.
        int effectiveWindow = newContextWindowSize - Math.max(0, maxOutputTokens);
        if (effectiveWindow <= 0) {
            effectiveWindow = newContextWindowSize;
        }
        int floorAdjusted = Math.max((int) (effectiveWindow * thresholdFraction), MINIMUM_CONTEXT_LENGTH);
        int thresholdTokens = floorAdjusted >= effectiveWindow
            ? Math.max(1, Math.min((int) (effectiveWindow * MIN_CTX_TRIGGER_RATIO), effectiveWindow - 1))
            : floorAdjusted;
        this.compressionThresholdChars = thresholdTokens * CHARS_PER_TOKEN;
        log.info("Compression threshold recalculated for context window {} (model={}, fraction={}, outputReservation={}): thresholdTokens={}, thresholdChars={}",
            newContextWindowSize, model, thresholdFraction, maxOutputTokens, thresholdTokens, this.compressionThresholdChars);
    }

    /**
     * P2-51: Returns the dynamic compression threshold in chars, or 0 if not yet set.
     * Callers should fall back to config-based {@code targetTokens * CHARS_PER_TOKEN} when this returns 0.
     *
     * @return the dynamic compression threshold in chars, or 0 if not set
     */
    int getCompressionThresholdChars() {
        return compressionThresholdChars;
    }

    // ── Compression decision logic ──

    /**
     * Proactive compression check — called after each tool batch in the turn loop,
     * BEFORE the next model call. Returns true if the estimated token count
     * exceeds the proactive compression threshold.
     * <p>
     * The threshold is computed as:
     * <pre>
     *   thresholdTokens = max((int)(contextWindowSize * 0.50), MINIMUM_CONTEXT_LENGTH)
     * </pre>
     * Hermes uses {@code threshold_percent = 0.50} with the 64K floor.
     *
     * @param estimatedTokens the estimated token count for the current context
     * @param contextWindowSize the model's context window size in tokens
     * @return true if proactive compression should be triggered
     */
    boolean shouldCompressProactive(int estimatedTokens, int contextWindowSize) {
        if (contextWindowSize <= 0) {
            return false;
        }
        int thresholdTokens = Math.max(
            (int) (contextWindowSize * PROACTIVE_THRESHOLD_FRACTION),
            MINIMUM_CONTEXT_LENGTH
        );
        if (estimatedTokens < thresholdTokens) {
            return false;
        }
        if (consecutiveLowSavings >= MAX_CONSECUTIVE_LOW_SAVINGS) {
            log.warn("Proactive compression skipped — last {} compressions saved <{}% each. "
                + "Consider /new to start a fresh session, or /compress for focused compression.",
                consecutiveLowSavings, (int) LOW_SAVINGS_THRESHOLD_PCT);
            return false;
        }
        return true;
    }

    /**
     * Reactive compression check — called on CONTEXT_OVERFLOW or PAYLOAD_TOO_LARGE errors.
     * Returns true if compression should be attempted (respecting anti-thrashing).
     *
     * @param estimatedTokens the estimated token count for the current context
     * @return true if compression should be attempted
     */
    boolean shouldCompress(int estimatedTokens) {
        if (consecutiveLowSavings >= MAX_CONSECUTIVE_LOW_SAVINGS) {
            log.warn("Compression skipped — last {} compressions saved <{}% each. "
                + "Consider /new to start a fresh session, or /compress for focused compression.",
                consecutiveLowSavings, (int) LOW_SAVINGS_THRESHOLD_PCT);
            return false;
        }
        // P-11: transient structural no-op backoff — defer the scan without
        // striking the ineffective-strike breaker (Hermes #93022).
        if (isStructuralNoOpBackoffActive()) {
            log.debug("Compression deferred — structural no-op backoff active");
            return false;
        }
        return true;
    }

    // ── Anti-thrashing ──

    /**
     * Anti-thrashing: record the savings from a compression and update the counter.
     * <p>
     * Mirrors Hermes:
     * <pre>
     *   savings_pct = (saved_estimate / display_tokens * 100) if display_tokens > 0 else 0
     *   self._last_compression_savings_pct = savings_pct
     *   if savings_pct < 10:
     *       self._ineffective_compression_count += 1
     *   else:
     *       self._ineffective_compression_count = 0
     * </pre>
     *
     * @param originalTokens the token estimate before compression
     * @param compressedTokens the token estimate after compression
     */
    void recordCompressionSavings(int originalTokens, int compressedTokens) {
        double savingsPct = originalTokens > 0
            ? ((double) (originalTokens - compressedTokens) / originalTokens * 100.0)
            : 0.0;
        this.lastCompressionSavingsPct = savingsPct;
        // Hermes parity: increment compression_count after each compression.
        if (savingsPct >= LOW_SAVINGS_THRESHOLD_PCT) {
            globalCompressionCount.incrementAndGet();
        }
        if (savingsPct < LOW_SAVINGS_THRESHOLD_PCT) {
            this.consecutiveLowSavings++;
            log.warn("Low-savings compression: {}% saved (consecutive count now {}/{})",
                String.format("%.1f", savingsPct), consecutiveLowSavings, MAX_CONSECUTIVE_LOW_SAVINGS);
        } else {
            this.consecutiveLowSavings = 0;
        }
    }

    // ── P-11 (Hermes f778c0d941 #93022): compression outcome states + structural no-op backoff ──

    /** Hermes _STRUCTURAL_NO_OP_BACKOFF_SECONDS = 300.0. */
    static final double STRUCTURAL_NO_OP_BACKOFF_SECONDS = 300.0;

    /**
     * Explicit outcome of one compression attempt (Hermes verdict vocabulary):
     * CHANGED (structure rewritten), REDUCED (bytes shrank), REFUSED (would
     * grow / rejected), NOOP (nothing eligible — structural no-op).
     */
    enum Outcome { CHANGED, REDUCED, REFUSED, NOOP }

    private volatile long structuralNoOpBackoffUntil = 0;

    /**
     * P-11: a structural no-op (too few messages / no compressible window)
     * means compression was never really attempted — record a transient
     * backoff but do NOT strike the ineffective-strike breaker. Counting
     * no-ops as strikes permanently disarms auto-compaction on short
     * sessions even after they later grow real compressible material.
     */
    void recordStructuralNoOp(String reason) {
        this.structuralNoOpBackoffUntil = System.nanoTime()
            + (long) (STRUCTURAL_NO_OP_BACKOFF_SECONDS * 1_000_000_000L);
        log.warn("Compression skipped ({}): structural no-op backoff {:.0f}s",
            reason, STRUCTURAL_NO_OP_BACKOFF_SECONDS);
    }

    /** True while a structural no-op backoff window is active. */
    boolean isStructuralNoOpBackoffActive() {
        return System.nanoTime() < structuralNoOpBackoffUntil;
    }

    /**
     * A completed compaction boundary is proof the transcript WAS
     * compressible — lift any pending structural no-op backoff (Hermes
     * record_completed_compaction clears it alongside the bookkeeping).
     */
    void clearStructuralNoOpBackoff() {
        this.structuralNoOpBackoffUntil = 0;
    }

    /**
     * Classify an attempt outcome from before/after sizes. Only genuine
     * attempted-but-underperformed results strike the breaker (REDUCED with
     * low savings); NOOP/REFUSED never do.
     */
    Outcome classifyOutcome(int originalTokens, int compressedTokens, boolean structureChanged) {
        if (structureChanged && compressedTokens < originalTokens) return Outcome.REDUCED;
        if (structureChanged) return Outcome.CHANGED;
        if (compressedTokens > originalTokens) return Outcome.REFUSED;
        return Outcome.NOOP;
    }

    /**
     * Reset the anti-thrashing counter. Called when a new session starts or when
     * the user manually triggers compression via /compress.
     */
    void resetAntiThrashing() {
        this.consecutiveLowSavings = 0;
        this.lastCompressionSavingsPct = 100.0;
    }

    /** Returns the consecutive low-savings compression count for monitoring. */
    int getConsecutiveLowSavings() {
        return consecutiveLowSavings;
    }

    /** Returns the last compression savings percentage (0-100). */
    double getLastCompressionSavingsPct() {
        return lastCompressionSavingsPct;
    }

    // ── Compression failure cooldown (h60) ──

    /** Reset compression failure cooldown when the runtime/model switches. */
    void resetCompressionFailureCooldown(String modelKey) {
        if (modelKey == null || !modelKey.equals(this.compressionCooldownModelKey)) {
            this.compressionCooldownModelKey = modelKey;
            this.compressionFailureCooldownUntil = 0;
            log.debug("Compression failure cooldown reset for model key: {}", modelKey);
        }
    }

    /** Check if compression is in a failure cooldown. */
    boolean isCompressionFailureCooldownActive() {
        return compressionFailureCooldownUntil > 0
            && System.currentTimeMillis() < compressionFailureCooldownUntil;
    }

    /** Set the compression failure cooldown for the current model. */
    void setCompressionFailureCooldown(long durationMs) {
        this.compressionFailureCooldownUntil = System.currentTimeMillis() + durationMs;
    }

    // ── Summary budget ──

    /**
     * Computes a scaled summary token budget proportional to the compressed content.
     * Mirrors the original project's _SUMMARY_RATIO and _SUMMARY_TOKENS_CEILING.
     */
    int computeSummaryBudget(int compressedChars) {
        int compressedTokens = compressedChars / CHARS_PER_TOKEN;
        int scaled = (int) (compressedTokens * SUMMARY_RATIO);
        return Math.min(Math.max(scaled, MIN_SUMMARY_TOKENS), SUMMARY_TOKENS_CEILING);
    }

    // ── Global compression count ──

    /** Returns the global compression count (used for protectFirstN decay). */
    int getGlobalCompressionCount() {
        return globalCompressionCount.get();
    }

    /** Increments the global compression count (used after a successful compression). */
    void incrementGlobalCompressionCount() {
        globalCompressionCount.incrementAndGet();
    }
}