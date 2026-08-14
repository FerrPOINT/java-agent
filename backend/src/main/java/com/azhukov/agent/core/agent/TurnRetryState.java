package com.azhukov.agent.core.agent;

/**
 * One-shot recovery guards for a single model API-call retry cycle.
 * <p>
 * Ported from Hermes' turn_retry_state.py. Each guard fires its recovery
 * branch at most once per retry cycle, preventing infinite loops on
 * recovery actions that themselves might fail.
 * <p>
 * Guards relevant to the OpenAI-compatible client:
 * <ul>
 * <li>{@code authRetryAttempted} — refresh credentials on 401 (covers generic auth)</li>
 * <li>{@code thinkingSigRetryAttempted} — strip thinking blocks from messages and retry</li>
 * <li>{@code imageShrinkRetryAttempted} — remove/shrink image content and retry</li>
 * <li>{@code multimodalToolContentRetryAttempted} — strip multimodal content from tool results</li>
 * <li>{@code primaryRecoveryAttempted} — recreate HTTP client after connection failure</li>
 * <li>{@code hasRetried429} — don't retry 429 rate limit more than once without credential rotation</li>
 * <li>{@code compressionRestartAttempted} — restart with compressed messages (already existed as local var)</li>
 * <li>{@code lengthContinuationAttempted} — retry with length-continuation prompt</li>
 * </ul>
 * <p>
 * Additional one-shot guards (parity with Hermes turn_retry_state.py — 14 total):
 * <ul>
 * <li>{@code invalidEncryptedContentRetryAttempted} — strip codex reasoning replay</li>
 * <li>{@code oauth1mBetaRetryAttempted} — disable 1M context beta</li>
 * <li>{@code llamaCppGrammarRetryAttempted} — strip tool schema patterns</li>
 * <li>{@code codexAuthRetryAttempted} — Codex OAuth refresh (separate from generic auth)</li>
 * <li>{@code anthropicAuthRetryAttempted} — Anthropic OAuth refresh</li>
 * <li>{@code copilotAuthRetryAttempted} — Copilot auth refresh</li>
 * </ul>
 * <p>
 * Thinking-specific counters (ported from Hermes
 * {@code _thinking_prefill_retries} and {@code _incomplete_scratchpad_retries}):
 * <ul>
 * <li>{@code thinkingPrefillRetries} — how many times prefill continuation has been
 *     attempted for thinking-only responses (max 2, matching Hermes)</li>
 * <li>{@code incompleteScratchpadRetries} — how many times we've retried after
 *     detecting an incomplete {@code <REASONING_SCRATCHPAD>} (max 2, matching Hermes)</li>
 * </ul>
 */
public class TurnRetryState {

    // ── Auth / credential refresh guards ──────────────────────────────
    private boolean authRetryAttempted = false;
    // Per-provider OAuth refresh guards (separate from generic auth)
    private boolean codexAuthRetryAttempted = false;
    private boolean anthropicAuthRetryAttempted = false;
    private boolean copilotAuthRetryAttempted = false;

    // ── Format / payload recovery guards ──────────────────────────────
    private boolean thinkingSigRetryAttempted = false;
    private boolean imageShrinkRetryAttempted = false;
    private boolean multimodalToolContentRetryAttempted = false;
    private boolean invalidEncryptedContentRetryAttempted = false;
    private boolean oauth1mBetaRetryAttempted = false;
    private boolean llamaCppGrammarRetryAttempted = false;

    // ── Transport / rate-limit recovery ───────────────────────────────
    private boolean primaryRecoveryAttempted = false;
    private boolean hasRetried429 = false;

    // ── Restart signals ───────────────────────────────────────────────
    // Compression restart is now a counter (up to 3), matching Hermes max_compression_attempts = 3.
    // The old one-shot boolean compressionRestartAttempted is kept for backward compatibility
    // but the runtime now uses the counter via getCompressionAttempts()/incrementCompressionAttempts().
    private boolean compressionRestartAttempted = false;
    private int compressionAttempts = 0;
    private boolean lengthContinuationAttempted = false;

    // ── Thinking-specific retry counters (parity with Hermes) ─────────
    // _thinking_prefill_retries: max 2 prefill-continuation attempts
    private int thinkingPrefillRetries = 0;
    // _incomplete_scratchpad_retries: max 2 retries for incomplete scratchpad
    private int incompleteScratchpadRetries = 0;

    // ── Empty response retry counter (parity with Hermes _empty_content_retries: max 3) ──
    private int emptyResponseRetries = 0;

    public boolean isAuthRetryAttempted() { return authRetryAttempted; }
    public void setAuthRetryAttempted(boolean v) { this.authRetryAttempted = v; }

    public boolean isCodexAuthRetryAttempted() { return codexAuthRetryAttempted; }
    public void setCodexAuthRetryAttempted(boolean v) { this.codexAuthRetryAttempted = v; }

    public boolean isAnthropicAuthRetryAttempted() { return anthropicAuthRetryAttempted; }
    public void setAnthropicAuthRetryAttempted(boolean v) { this.anthropicAuthRetryAttempted = v; }

    public boolean isCopilotAuthRetryAttempted() { return copilotAuthRetryAttempted; }
    public void setCopilotAuthRetryAttempted(boolean v) { this.copilotAuthRetryAttempted = v; }

    public boolean isThinkingSigRetryAttempted() { return thinkingSigRetryAttempted; }
    public void setThinkingSigRetryAttempted(boolean v) { this.thinkingSigRetryAttempted = v; }

    public boolean isImageShrinkRetryAttempted() { return imageShrinkRetryAttempted; }
    public void setImageShrinkRetryAttempted(boolean v) { this.imageShrinkRetryAttempted = v; }

    public boolean isMultimodalToolContentRetryAttempted() { return multimodalToolContentRetryAttempted; }
    public void setMultimodalToolContentRetryAttempted(boolean v) { this.multimodalToolContentRetryAttempted = v; }

    public boolean isInvalidEncryptedContentRetryAttempted() { return invalidEncryptedContentRetryAttempted; }
    public void setInvalidEncryptedContentRetryAttempted(boolean v) { this.invalidEncryptedContentRetryAttempted = v; }

    public boolean isOauth1mBetaRetryAttempted() { return oauth1mBetaRetryAttempted; }
    public void setOauth1mBetaRetryAttempted(boolean v) { this.oauth1mBetaRetryAttempted = v; }

    public boolean isLlamaCppGrammarRetryAttempted() { return llamaCppGrammarRetryAttempted; }
    public void setLlamaCppGrammarRetryAttempted(boolean v) { this.llamaCppGrammarRetryAttempted = v; }

    public boolean isPrimaryRecoveryAttempted() { return primaryRecoveryAttempted; }
    public void setPrimaryRecoveryAttempted(boolean v) { this.primaryRecoveryAttempted = v; }

    public boolean isHasRetried429() { return hasRetried429; }
    public void setHasRetried429(boolean v) { this.hasRetried429 = v; }

    public boolean isCompressionRestartAttempted() { return compressionRestartAttempted; }
    public void setCompressionRestartAttempted(boolean v) { this.compressionRestartAttempted = v; }

    /**
     * Returns the number of compression attempts made so far in this retry cycle.
     * Mirrors Hermes {@code compression_attempts} local counter (max 3).
     */
    public int getCompressionAttempts() { return compressionAttempts; }
    public void setCompressionAttempts(int v) { this.compressionAttempts = v; }
    public void incrementCompressionAttempts() { this.compressionAttempts++; }

    public boolean isLengthContinuationAttempted() { return lengthContinuationAttempted; }
    public void setLengthContinuationAttempted(boolean v) { this.lengthContinuationAttempted = v; }

    public int getThinkingPrefillRetries() { return thinkingPrefillRetries; }
    public void setThinkingPrefillRetries(int v) { this.thinkingPrefillRetries = v; }
    public void incrementThinkingPrefillRetries() { this.thinkingPrefillRetries++; }

    public int getIncompleteScratchpadRetries() { return incompleteScratchpadRetries; }
    public void setIncompleteScratchpadRetries(int v) { this.incompleteScratchpadRetries = v; }
    public void incrementIncompleteScratchpadRetries() { this.incompleteScratchpadRetries++; }

    public int getEmptyResponseRetries() { return emptyResponseRetries; }
    public void setEmptyResponseRetries(int v) { this.emptyResponseRetries = v; }
    public void incrementEmptyResponseRetries() { this.emptyResponseRetries++; }

    /**
     * Returns true if any one-shot guard has been consumed.
     */
    public boolean anyGuardConsumed() {
        return authRetryAttempted || codexAuthRetryAttempted || anthropicAuthRetryAttempted
            || copilotAuthRetryAttempted || thinkingSigRetryAttempted || imageShrinkRetryAttempted
            || multimodalToolContentRetryAttempted || invalidEncryptedContentRetryAttempted
            || oauth1mBetaRetryAttempted || llamaCppGrammarRetryAttempted
            || primaryRecoveryAttempted || hasRetried429
            || compressionRestartAttempted || compressionAttempts > 0 || lengthContinuationAttempted;
    }

    @Override
    public String toString() {
        return "TurnRetryState{auth=" + authRetryAttempted
            + ", codexAuth=" + codexAuthRetryAttempted
            + ", anthropicAuth=" + anthropicAuthRetryAttempted
            + ", copilotAuth=" + copilotAuthRetryAttempted
            + ", thinkingSig=" + thinkingSigRetryAttempted
            + ", imageShrink=" + imageShrinkRetryAttempted
            + ", multimodal=" + multimodalToolContentRetryAttempted
            + ", invalidEncrypted=" + invalidEncryptedContentRetryAttempted
            + ", oauth1mBeta=" + oauth1mBetaRetryAttempted
            + ", llamaCppGrammar=" + llamaCppGrammarRetryAttempted
            + ", primaryRecovery=" + primaryRecoveryAttempted
            + ", retried429=" + hasRetried429
            + ", compression=" + compressionRestartAttempted
            + ", compressionAttempts=" + compressionAttempts
            + ", lengthCont=" + lengthContinuationAttempted
            + ", thinkingPrefill=" + thinkingPrefillRetries
            + ", incompleteScratchpad=" + incompleteScratchpadRetries
            + ", emptyResponse=" + emptyResponseRetries + "}";
    }
}