package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnRetryStateTest {

    @Test
    void allGuardsStartFalse() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isAuthRetryAttempted()).isFalse();
        assertThat(state.isCodexAuthRetryAttempted()).isFalse();
        assertThat(state.isAnthropicAuthRetryAttempted()).isFalse();
        assertThat(state.isCopilotAuthRetryAttempted()).isFalse();
        assertThat(state.isThinkingSigRetryAttempted()).isFalse();
        assertThat(state.isImageShrinkRetryAttempted()).isFalse();
        assertThat(state.isMultimodalToolContentRetryAttempted()).isFalse();
        assertThat(state.isInvalidEncryptedContentRetryAttempted()).isFalse();
        assertThat(state.isOauth1mBetaRetryAttempted()).isFalse();
        assertThat(state.isLlamaCppGrammarRetryAttempted()).isFalse();
        assertThat(state.isPrimaryRecoveryAttempted()).isFalse();
        assertThat(state.isHasRetried429()).isFalse();
        assertThat(state.isCompressionRestartAttempted()).isFalse();
        assertThat(state.isLengthContinuationAttempted()).isFalse();
        assertThat(state.getThinkingPrefillRetries()).isZero();
        assertThat(state.getIncompleteScratchpadRetries()).isZero();
        assertThat(state.getEmptyResponseRetries()).isZero();
        assertThat(state.anyGuardConsumed()).isFalse();
    }

    @Test
    void settingGuardMakesItTrue() {
        TurnRetryState state = new TurnRetryState();
        state.setAuthRetryAttempted(true);
        assertThat(state.isAuthRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void anyGuardConsumedTrueWhenAnyGuardSet() {
        TurnRetryState state = new TurnRetryState();
        state.setThinkingSigRetryAttempted(true);
        assertThat(state.anyGuardConsumed()).isTrue();
        state = new TurnRetryState();
        state.setHasRetried429(true);
        assertThat(state.anyGuardConsumed()).isTrue();
        state = new TurnRetryState();
        state.setCompressionRestartAttempted(true);
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void anyGuardConsumedFalseWhenNoGuardsSet() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.anyGuardConsumed()).isFalse();
    }

    @Test
    void toStringContainsAllGuards() {
        TurnRetryState state = new TurnRetryState();
        state.setAuthRetryAttempted(true);
        state.setHasRetried429(true);
        String str = state.toString();
        assertThat(str).contains("auth=true")
            .contains("retried429=true")
            .contains("thinkingSig=false");
    }

    @Test
    void guardsAreIndependent() {
        TurnRetryState state = new TurnRetryState();
        state.setAuthRetryAttempted(true);
        assertThat(state.isThinkingSigRetryAttempted()).isFalse();
        state.setThinkingSigRetryAttempted(true);
        assertThat(state.isImageShrinkRetryAttempted()).isFalse();
        state.setImageShrinkRetryAttempted(true);
        assertThat(state.isMultimodalToolContentRetryAttempted()).isFalse();
        state.setMultimodalToolContentRetryAttempted(true);
        assertThat(state.isPrimaryRecoveryAttempted()).isFalse();
        state.setPrimaryRecoveryAttempted(true);
        assertThat(state.isHasRetried429()).isFalse();
        state.setHasRetried429(true);
        assertThat(state.isCompressionRestartAttempted()).isFalse();
        state.setCompressionRestartAttempted(true);
        assertThat(state.isLengthContinuationAttempted()).isFalse();
        state.setLengthContinuationAttempted(true);
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void thinkingPrefillRetries_canBeIncremented() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.getThinkingPrefillRetries()).isZero();
        state.incrementThinkingPrefillRetries();
        assertThat(state.getThinkingPrefillRetries()).isEqualTo(1);
        state.incrementThinkingPrefillRetries();
        assertThat(state.getThinkingPrefillRetries()).isEqualTo(2);
        state.setThinkingPrefillRetries(0);
        assertThat(state.getThinkingPrefillRetries()).isZero();
    }

    @Test
    void incompleteScratchpadRetries_canBeIncremented() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.getIncompleteScratchpadRetries()).isZero();
        state.incrementIncompleteScratchpadRetries();
        assertThat(state.getIncompleteScratchpadRetries()).isEqualTo(1);
        state.incrementIncompleteScratchpadRetries();
        assertThat(state.getIncompleteScratchpadRetries()).isEqualTo(2);
        state.setIncompleteScratchpadRetries(0);
        assertThat(state.getIncompleteScratchpadRetries()).isZero();
    }

    @Test
    void toStringContainsThinkingCounters() {
        TurnRetryState state = new TurnRetryState();
        state.incrementThinkingPrefillRetries();
        state.incrementIncompleteScratchpadRetries();
        String str = state.toString();
        assertThat(str).contains("thinkingPrefill=1");
        assertThat(str).contains("incompleteScratchpad=1");
    }

    // ── New guard tests (Part A: 6 additional one-shot guards) ──

    @Test
    void codexAuthRetryAttempted_isIndependentFromAuthRetry() {
        TurnRetryState state = new TurnRetryState();
        state.setAuthRetryAttempted(true);
        assertThat(state.isCodexAuthRetryAttempted()).isFalse();
        state.setCodexAuthRetryAttempted(true);
        assertThat(state.isCodexAuthRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void anthropicAuthRetryAttempted_isIndependent() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isAnthropicAuthRetryAttempted()).isFalse();
        state.setAnthropicAuthRetryAttempted(true);
        assertThat(state.isAnthropicAuthRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void copilotAuthRetryAttempted_isIndependent() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isCopilotAuthRetryAttempted()).isFalse();
        state.setCopilotAuthRetryAttempted(true);
        assertThat(state.isCopilotAuthRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void invalidEncryptedContentRetryAttempted_isIndependent() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isInvalidEncryptedContentRetryAttempted()).isFalse();
        state.setInvalidEncryptedContentRetryAttempted(true);
        assertThat(state.isInvalidEncryptedContentRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void oauth1mBetaRetryAttempted_isIndependent() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isOauth1mBetaRetryAttempted()).isFalse();
        state.setOauth1mBetaRetryAttempted(true);
        assertThat(state.isOauth1mBetaRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void llamaCppGrammarRetryAttempted_isIndependent() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.isLlamaCppGrammarRetryAttempted()).isFalse();
        state.setLlamaCppGrammarRetryAttempted(true);
        assertThat(state.isLlamaCppGrammarRetryAttempted()).isTrue();
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void allNewGuardsAreIndependent() {
        TurnRetryState state = new TurnRetryState();
        state.setCodexAuthRetryAttempted(true);
        assertThat(state.isAnthropicAuthRetryAttempted()).isFalse();
        assertThat(state.isCopilotAuthRetryAttempted()).isFalse();
        assertThat(state.isInvalidEncryptedContentRetryAttempted()).isFalse();
        assertThat(state.isOauth1mBetaRetryAttempted()).isFalse();
        assertThat(state.isLlamaCppGrammarRetryAttempted()).isFalse();
        state.setAnthropicAuthRetryAttempted(true);
        assertThat(state.isCopilotAuthRetryAttempted()).isFalse();
        state.setCopilotAuthRetryAttempted(true);
        assertThat(state.isInvalidEncryptedContentRetryAttempted()).isFalse();
        state.setInvalidEncryptedContentRetryAttempted(true);
        assertThat(state.isOauth1mBetaRetryAttempted()).isFalse();
        state.setOauth1mBetaRetryAttempted(true);
        assertThat(state.isLlamaCppGrammarRetryAttempted()).isFalse();
        state.setLlamaCppGrammarRetryAttempted(true);
        // All 14 guards now consumed
        assertThat(state.anyGuardConsumed()).isTrue();
    }

    @Test
    void emptyResponseRetries_canBeIncremented() {
        TurnRetryState state = new TurnRetryState();
        assertThat(state.getEmptyResponseRetries()).isZero();
        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(1);
        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(2);
        state.incrementEmptyResponseRetries();
        assertThat(state.getEmptyResponseRetries()).isEqualTo(3);
        state.setEmptyResponseRetries(0);
        assertThat(state.getEmptyResponseRetries()).isZero();
    }

    @Test
    void toStringContainsAllNewGuards() {
        TurnRetryState state = new TurnRetryState();
        state.setCodexAuthRetryAttempted(true);
        state.setAnthropicAuthRetryAttempted(true);
        state.setCopilotAuthRetryAttempted(true);
        state.setInvalidEncryptedContentRetryAttempted(true);
        state.setOauth1mBetaRetryAttempted(true);
        state.setLlamaCppGrammarRetryAttempted(true);
        state.incrementEmptyResponseRetries();
        String str = state.toString();
        assertThat(str).contains("codexAuth=true")
            .contains("anthropicAuth=true")
            .contains("copilotAuth=true")
            .contains("invalidEncrypted=true")
            .contains("oauth1mBeta=true")
            .contains("llamaCppGrammar=true")
            .contains("emptyResponse=1");
    }

    @Test
    void anyGuardConsumed_includesNewGuards() {
        // Verify each new guard alone triggers anyGuardConsumed
        assertThat(new TurnRetryState() {{
            setCodexAuthRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
        assertThat(new TurnRetryState() {{
            setAnthropicAuthRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
        assertThat(new TurnRetryState() {{
            setCopilotAuthRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
        assertThat(new TurnRetryState() {{
            setInvalidEncryptedContentRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
        assertThat(new TurnRetryState() {{
            setOauth1mBetaRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
        assertThat(new TurnRetryState() {{
            setLlamaCppGrammarRetryAttempted(true);
        }}.anyGuardConsumed()).isTrue();
    }
}