package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmptyResponseGuardTest {

    private EmptyResponseGuard guard;

    @BeforeEach
    void setUp() {
        guard = new EmptyResponseGuard();
    }

    // ─── deterministicEmpty ───

    @Test
    void deterministicEmpty_noAttempts_returnsFalse() {
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_singleAttempt_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_twoIdenticalZeroTokenAttempts_returnsTrue() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    @Test
    void deterministicEmpty_twoAttemptsDifferentModels_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-2", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_twoAttemptsDifferentProviders_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-2", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_twoAttemptsDifferentFinishReasons_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "LENGTH", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_nonZeroOutputTokens_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 50L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_nullOutputTokens_failOpen_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", null);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", null);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_oneNullOneZeroOutputTokens_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", null);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void deterministicEmpty_threeIdenticalZeroTokenAttempts_returnsTrue() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    @Test
    void deterministicEmpty_nullModelAndProvider_stillDeterministic() {
        guard.recordEmptyAttempt(null, null, "STOP", 0L);
        guard.recordEmptyAttempt(null, null, "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    @Test
    void deterministicEmpty_nullFinishReason_stillDeterministic() {
        guard.recordEmptyAttempt("model-1", "provider-1", null, 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", null, 0L);
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    @Test
    void deterministicEmpty_nullVsNonNullFinishReason_returnsFalse() {
        guard.recordEmptyAttempt("model-1", "provider-1", null, 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    // ─── reset ───

    @Test
    void reset_clearsAttempts() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();

        guard.reset();
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void reset_onEmptyGuard_noException() {
        guard.reset();
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void reset_thenRecordAgain_deterministicEmptyWorks() {
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.recordEmptyAttempt("model-1", "provider-1", "STOP", 0L);
        guard.reset();
        guard.recordEmptyAttempt("model-2", "provider-2", "STOP", 0L);
        guard.recordEmptyAttempt("model-2", "provider-2", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    // ─── MAX_TRACKED behavior ───

    @Test
    void recordEmptyAttempt_moreThanMaxTracked_keepsLastFive() {
        // MAX_TRACKED is 5 — record 7 attempts with different models
        for (int i = 0; i < 7; i++) {
            guard.recordEmptyAttempt("model-" + i, "provider", "STOP", 0L);
        }
        // The last 5 are model-2 through model-6 — they're all different
        // so deterministicEmpty should be false
        assertThat(guard.deterministicEmpty()).isFalse();
    }

    @Test
    void recordEmptyAttempt_moreThanMaxTracked_lastFiveIdentical_returnsTrue() {
        // Record 3 attempts with different models, then 5 identical
        guard.recordEmptyAttempt("other-1", "provider", "STOP", 0L);
        guard.recordEmptyAttempt("other-2", "provider", "STOP", 0L);
        guard.recordEmptyAttempt("other-3", "provider", "STOP", 0L);
        for (int i = 0; i < 5; i++) {
            guard.recordEmptyAttempt("model-x", "provider", "STOP", 0L);
        }
        // The first 3 are evicted, the last 5 are identical
        assertThat(guard.deterministicEmpty()).isTrue();
    }

    // ─── DEFAULT_EMPTY_RETRY_BUDGET constant ───

    @Test
    void defaultEmptyRetryBudget_isThree() {
        assertThat(EmptyResponseGuard.DEFAULT_EMPTY_RETRY_BUDGET).isEqualTo(3);
    }
}