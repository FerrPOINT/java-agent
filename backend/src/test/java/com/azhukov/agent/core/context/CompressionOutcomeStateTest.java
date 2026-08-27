package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P-11: outcome states + structural no-op backoff without breaker strikes. */
class CompressionOutcomeStateTest {

    private final CompressionPolicy policy = new CompressionPolicy();

    @Test
    void outcomeClassification() {
        assertThat(policy.classifyOutcome(1000, 400, true)).isEqualTo(CompressionPolicy.Outcome.REDUCED);
        assertThat(policy.classifyOutcome(1000, 1200, true)).isEqualTo(CompressionPolicy.Outcome.CHANGED);
        assertThat(policy.classifyOutcome(1000, 1200, false)).isEqualTo(CompressionPolicy.Outcome.REFUSED);
        assertThat(policy.classifyOutcome(1000, 1000, false)).isEqualTo(CompressionPolicy.Outcome.NOOP);
    }

    @Test
    void structuralNoOpDefersWithoutStriking() {
        policy.recordStructuralNoOp("empty compressible window");
        assertThat(policy.isStructuralNoOpBackoffActive()).isTrue();
        // shouldCompress deferred during backoff...
        assertThat(policy.shouldCompress(100_000)).isFalse();
        // ...but the ineffective-strike counter was NOT touched — a manual /compress
        // path (which skips the breaker check) still runs, and after clearing,
        // auto-compression resumes.
        policy.clearStructuralNoOpBackoff();
        assertThat(policy.shouldCompress(100_000)).isTrue();
    }

    @Test
    void breakerStillStrikesOnGenuineLowSavings() {
        policy.recordCompressionSavings(1000, 950); // 5% < 10% → strike
        policy.recordCompressionSavings(1000, 950);
        assertThat(policy.shouldCompress(100_000)).isFalse();
    }

    @Test
    void completedCompactionLiftsBackoff() {
        policy.recordStructuralNoOp("empty compressible window");
        policy.recordCompressionSavings(1000, 200); // 80% — real boundary
        // recordCompressionSavings itself doesn't clear; the compressor calls
        // clearStructuralNoOpBackoff() at the committed boundary — mirror that:
        policy.clearStructuralNoOpBackoff();
        assertThat(policy.isStructuralNoOpBackoffActive()).isFalse();
    }
}
