package com.azhukov.agent.core.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rev-129 Hermes parity: _compute_threshold_tokens / _effective_threshold_percent
 * (context_compressor.py:3044, :3062). The preflight trigger and the compressor
 * must resolve the SAME threshold: per-model overrides → small-context floor →
 * effective input budget (window − reserved output) → 64K floor → degenerate 85% rule.
 */
class CompressionPolicyThresholdResolutionTest {

    @Test
    @DisplayName("128K model with default 0.50: small-ctx floor raises trigger to 96K, not 64K")
    void smallContextFloorApplies() {
        int threshold = CompressionPolicy.computeThresholdTokens(
            131_072, 0.50, "kimi-k2", null, 0);
        // 131072 * 0.75 = 98304 — floor raises the default 50%
        assertThat(threshold).isEqualTo(98_304);
    }

    @Test
    @DisplayName("512K+ model keeps the configured 0.50 (no small-ctx floor)")
    void largeContextKeepsConfigured() {
        int threshold = CompressionPolicy.computeThresholdTokens(
            524_288, 0.50, "big-model", null, 0);
        assertThat(threshold).isEqualTo(Math.max((int) (524_288 * 0.50), 64_000));
    }

    @Test
    @DisplayName("explicit higher configured threshold wins over the floor")
    void explicitHigherWins() {
        int threshold = CompressionPolicy.computeThresholdTokens(
            131_072, 0.85, "explicit", null, 0);
        assertThat(threshold).isEqualTo((int) (131_072 * 0.85));
    }

    @Test
    @DisplayName("per-model override resolves before the floor")
    void perModelOverrideResolves() {
        int threshold = CompressionPolicy.computeThresholdTokens(
            131_072, 0.50, "gpt-5.5-turbo", Map.of("gpt-5.5", 0.85), 0);
        // override 0.85 > floor 0.75 → 0.85 wins
        assertThat(threshold).isEqualTo((int) (131_072 * 0.85));
    }

    @Test
    @DisplayName("output reservation shrinks the effective budget (#43547)")
    void outputReservationShrinksBudget() {
        int window = 131_072;
        int reserved = 32_768;
        int effective = window - reserved; // 98304
        int threshold = CompressionPolicy.computeThresholdTokens(
            window, 0.50, "m", null, reserved);
        // floor 0.75 on the effective budget: 98304*0.75=73728
        assertThat(threshold).isEqualTo((int) (effective * 0.75));
    }

    @Test
    @DisplayName("degenerate window: 64K floor fills the budget → 85% rule (#14690)")
    void degenerateWindowUses85PercentRule() {
        int window = 64_000; // floor == window → can never fire
        int threshold = CompressionPolicy.computeThresholdTokens(
            window, 0.50, "small-model", null, 0);
        // 85% of 64000 = 54400, capped at window-1
        assertThat(threshold).isEqualTo(Math.min((int) (window * 0.85), window - 1));
    }

    @Test
    @DisplayName("tiny window still returns a reachable positive trigger")
    void tinyWindowReachable() {
        int threshold = CompressionPolicy.computeThresholdTokens(
            8_192, 0.50, "tiny", null, 0);
        assertThat(threshold).isGreaterThan(0).isLessThan(8_192);
    }

    @Test
    @DisplayName("non-positive window returns 0")
    void nonPositiveWindow() {
        assertThat(CompressionPolicy.computeThresholdTokens(0, 0.5, "m", null, 0)).isZero();
        assertThat(CompressionPolicy.computeThresholdTokens(-5, 0.5, "m", null, 0)).isZero();
    }
}
