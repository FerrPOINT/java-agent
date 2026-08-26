package com.azhukov.agent.core.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit tests for {@link CompressionPolicy} — the policy/threshold logic
 * extracted from {@link DefaultContextCompressor}.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>Dynamic threshold recalculation (recalculateThreshold, getCompressionThresholdChars)</li>
 *   <li>Proactive compression decision (shouldCompressProactive)</li>
 *   <li>Reactive compression decision (shouldCompress)</li>
 *   <li>Anti-thrashing counters (recordCompressionSavings, resetAntiThrashing, getConsecutiveLowSavings, getLastCompressionSavingsPct)</li>
 *   <li>Compression failure cooldown (resetCompressionFailureCooldown, isCompressionFailureCooldownActive, setCompressionFailureCooldown)</li>
 *   <li>Summary budget computation (computeSummaryBudget)</li>
 *   <li>Global compression count (getGlobalCompressionCount, incrementGlobalCompressionCount)</li>
 * </ul>
 */
class CompressionPolicyTest {

    private CompressionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new CompressionPolicy();
    }

    // ── Threshold recalculation ──

    @Nested
    @DisplayName("recalculateThreshold / getCompressionThresholdChars")
    class ThresholdRecalculation {

        @Test
        @DisplayName("Threshold is 0 by default")
        void thresholdIsZeroByDefault() {
            assertThat(policy.getCompressionThresholdChars()).isZero();
        }

        @Test
        @DisplayName("Threshold = max(context * 0.75, MINIMUM_CONTEXT_LENGTH) * CHARS_PER_TOKEN for 128K context")
        void thresholdFor128K() {
            policy.recalculateThreshold(131_072);
            int expected = (int) (131_072 * 0.75) * 4;
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Threshold uses 64K floor for small context windows (8K)")
        void smallContextUsesFloor() {
            policy.recalculateThreshold(8_192);
            int expected = Math.max((int) (8_192 * 0.75), CompressionPolicy.MINIMUM_CONTEXT_LENGTH) * 4;
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Threshold for large context window (200K) is above floor")
        void largeContextNoFloor() {
            policy.recalculateThreshold(200_000);
            int expected = (int) (200_000 * 0.75) * 4;
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(expected);
            assertThat(expected).isGreaterThan(CompressionPolicy.MINIMUM_CONTEXT_LENGTH * 4);
        }

        @Test
        @DisplayName("Threshold is updated when model switches")
        void thresholdUpdatedOnModelSwitch() {
            policy.recalculateThreshold(32_768);
            int first = policy.getCompressionThresholdChars();
            assertThat(first).isEqualTo(Math.max((int) (32_768 * 0.75), CompressionPolicy.MINIMUM_CONTEXT_LENGTH) * 4);

            policy.recalculateThreshold(131_072);
            int second = policy.getCompressionThresholdChars();
            assertThat(second).isGreaterThan(first);

            policy.recalculateThreshold(8_192);
            int third = policy.getCompressionThresholdChars();
            assertThat(third).isLessThan(second);
        }

        @Test
        @DisplayName("Non-positive context window size is ignored")
        void nonPositiveIgnored() {
            policy.recalculateThreshold(131_072);
            int original = policy.getCompressionThresholdChars();
            assertThat(original).isGreaterThan(0);

            policy.recalculateThreshold(0);
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(original);

            policy.recalculateThreshold(-1000);
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(original);
        }

        @Test
        @DisplayName("Threshold ratio is 75% of context window (in chars)")
        void thresholdRatioIs75Percent() {
            int contextWindow = 100_000;
            policy.recalculateThreshold(contextWindow);
            double ratio = (double) policy.getCompressionThresholdChars() / (contextWindow * 4);
            assertThat(ratio).isEqualTo(0.75, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    // ── Proactive compression ──

    @Nested
    @DisplayName("shouldCompressProactive")
    class ProactiveCompression {

        @Test
        @DisplayName("Returns false when estimated tokens below 50% threshold")
        void belowThreshold() {
            assertThat(policy.shouldCompressProactive(30_000, 128_000)).isFalse();
        }

        @Test
        @DisplayName("Returns true at 50% threshold")
        void atThreshold() {
            assertThat(policy.shouldCompressProactive(64_000, 128_000)).isTrue();
        }

        @Test
        @DisplayName("Returns true above 50% threshold")
        void aboveThreshold() {
            assertThat(policy.shouldCompressProactive(80_000, 128_000)).isTrue();
        }

        @Test
        @DisplayName("Returns false for invalid context window (0 or negative)")
        void invalidContextWindow() {
            assertThat(policy.shouldCompressProactive(100_000, 0)).isFalse();
            assertThat(policy.shouldCompressProactive(100_000, -1)).isFalse();
        }

        @Test
        @DisplayName("64K floor prevents premature compression on large-context models")
        void largeContextFloor() {
            // 200K * 0.50 = 100K threshold; 50K < 100K → no compression
            assertThat(policy.shouldCompressProactive(50_000, 200_000)).isFalse();
        }

        @Test
        @DisplayName("64K floor applies when context window is small (8K)")
        void smallContextFloor() {
            // 8K * 0.50 = 4K, floored to 64K → 70K >= 64K → compress
            assertThat(policy.shouldCompressProactive(70_000, 8_192)).isTrue();
            // 50K < 64K floor → no compression
            assertThat(policy.shouldCompressProactive(50_000, 8_192)).isFalse();
        }
    }

    // ── Reactive compression (shouldCompress) ──

    @Nested
    @DisplayName("shouldCompress (reactive)")
    class ReactiveCompression {

        @Test
        @DisplayName("Returns true when no low-savings compressions")
        void noLowSavings() {
            assertThat(policy.shouldCompress(100_000)).isTrue();
        }

        @Test
        @DisplayName("Returns false after 2 consecutive low-savings compressions")
        void afterTwoLowSavings() {
            policy.recordCompressionSavings(10_000, 9_500); // 5% → low
            policy.recordCompressionSavings(10_000, 9_200); // 8% → low
            assertThat(policy.shouldCompress(100_000)).isFalse();
        }
    }

    // ── Anti-thrashing ──

    @Nested
    @DisplayName("Anti-thrashing (recordCompressionSavings, resetAntiThrashing)")
    class AntiThrashing {

        @Test
        @DisplayName("Low savings (< 10%) increments counter")
        void lowSavingsIncrements() {
            policy.recordCompressionSavings(10_000, 9_500); // 5% → low
            assertThat(policy.getConsecutiveLowSavings()).isEqualTo(1);
            assertThat(policy.getLastCompressionSavingsPct()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("Good savings (>= 10%) resets counter to 0")
        void goodSavingsResetsCounter() {
            policy.recordCompressionSavings(10_000, 9_500); // 5% → low
            assertThat(policy.getConsecutiveLowSavings()).isEqualTo(1);
            policy.recordCompressionSavings(10_000, 5_000); // 50% → good
            assertThat(policy.getConsecutiveLowSavings()).isZero();
        }

        @Test
        @DisplayName("shouldCompressProactive also respects anti-thrashing")
        void proactiveRespectsAntiThrashing() {
            policy.recordCompressionSavings(10_000, 9_500);
            policy.recordCompressionSavings(10_000, 9_200);
            assertThat(policy.shouldCompressProactive(100_000, 128_000)).isFalse();
        }

        @Test
        @DisplayName("resetAntiThrashing clears counter and resets savings to 100%")
        void resetAntiThrashing() {
            policy.recordCompressionSavings(10_000, 9_500);
            policy.recordCompressionSavings(10_000, 9_200);
            assertThat(policy.getConsecutiveLowSavings()).isEqualTo(2);
            policy.resetAntiThrashing();
            assertThat(policy.getConsecutiveLowSavings()).isZero();
            assertThat(policy.getLastCompressionSavingsPct()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Exactly at 10% savings is NOT considered low (boundary)")
        void boundaryAt10Percent() {
            policy.recordCompressionSavings(10_000, 9_000); // 10% → not low
            assertThat(policy.getConsecutiveLowSavings()).isZero();
        }

        @Test
        @DisplayName("Just under 10% savings IS considered low (boundary)")
        void boundaryJustUnder10Percent() {
            policy.recordCompressionSavings(10_000, 9_010); // 9.9% → low
            assertThat(policy.getConsecutiveLowSavings()).isEqualTo(1);
        }

        @Test
        @DisplayName("Zero original tokens → savings = 0% → low")
        void zeroOriginalTokens() {
            policy.recordCompressionSavings(0, 0);
            assertThat(policy.getConsecutiveLowSavings()).isEqualTo(1);
            assertThat(policy.getLastCompressionSavingsPct()).isEqualTo(0.0);
        }
    }

    // ── Compression failure cooldown ──

    @Nested
    @DisplayName("Compression failure cooldown")
    class FailureCooldown {

        @Test
        @DisplayName("No active cooldown by default")
        void noCooldownByDefault() {
            assertThat(policy.isCompressionFailureCooldownActive()).isFalse();
        }

        @Test
        @DisplayName("Cooldown is active after setCompressionFailureCooldown with future deadline")
        void activeAfterSet() {
            policy.setCompressionFailureCooldown(60_000); // 60s in the future
            assertThat(policy.isCompressionFailureCooldownActive()).isTrue();
        }

        @Test
        @DisplayName("Cooldown with 0 duration is NOT active (deadline is now)")
        void zeroDurationNotActive() {
            policy.setCompressionFailureCooldown(0);
            // System.currentTimeMillis() + 0 is "now", which is not > now
            // The check is currentTimeMillis < deadline, so it may or may not be active
            // depending on the exact millisecond. But 0 means "now" so it should be inactive.
            // Wait a tiny bit to ensure we're past the deadline.
            // timing-assertion: verifies cooldown with 0 duration is inactive after deadline
            try { Thread.sleep(2); } catch (InterruptedException ignored) {}
            assertThat(policy.isCompressionFailureCooldownActive()).isFalse();
        }

        @Test
        @DisplayName("resetCompressionFailureCooldown with new model key clears cooldown")
        void resetWithNewModelKey() {
            policy.setCompressionFailureCooldown(60_000);
            assertThat(policy.isCompressionFailureCooldownActive()).isTrue();
            policy.resetCompressionFailureCooldown("model-B");
            assertThat(policy.isCompressionFailureCooldownActive()).isFalse();
        }

        @Test
        @DisplayName("resetCompressionFailureCooldown with same model key does NOT clear cooldown")
        void resetWithSameModelKey() {
            policy.resetCompressionFailureCooldown("model-A");
            policy.setCompressionFailureCooldown(60_000);
            assertThat(policy.isCompressionFailureCooldownActive()).isTrue();
            policy.resetCompressionFailureCooldown("model-A"); // same key → no reset
            assertThat(policy.isCompressionFailureCooldownActive()).isTrue();
        }

        @Test
        @DisplayName("recalculateThreshold resets cooldown when context window changes")
        void recalculateThresholdResetsCooldown() {
            policy.setCompressionFailureCooldown(60_000);
            assertThat(policy.isCompressionFailureCooldownActive()).isTrue();
            // recalculateThreshold calls resetCompressionFailureCooldown("ctx-" + newSize)
            policy.recalculateThreshold(131_072);
            assertThat(policy.isCompressionFailureCooldownActive()).isFalse();
        }

        @Test
        @DisplayName("Cooldown expires after duration")
        void cooldownExpires() throws InterruptedException {
            policy.setCompressionFailureCooldown(1); // 1ms
            // timing-assertion: verifies cooldown expires after duration
            Thread.sleep(5);
            assertThat(policy.isCompressionFailureCooldownActive()).isFalse();
        }
    }

    // ── Summary budget ──

    @Nested
    @DisplayName("computeSummaryBudget")
    class SummaryBudget {

        @Test
        @DisplayName("Small input gets minimum budget (MIN_SUMMARY_TOKENS = 2000)")
        void smallInputGetsMinimum() {
            int smallChars = 100; // 100 chars → 25 tokens → 25 * 0.20 = 5 tokens → floored to 2000
            int budget = policy.computeSummaryBudget(smallChars);
            assertThat(budget).isEqualTo(CompressionPolicy.MIN_SUMMARY_TOKENS);
        }

        @Test
        @DisplayName("Large input gets capped at SUMMARY_TOKENS_CEILING (10000)")
        void largeInputGetsCapped() {
            // 1M chars → 250K tokens → 250K * 0.20 = 50K → capped to 10_000
            int budget = policy.computeSummaryBudget(1_000_000);
            assertThat(budget).isEqualTo(CompressionPolicy.SUMMARY_TOKENS_CEILING);
        }

        @Test
        @DisplayName("Medium input gets proportional budget")
        void mediumInputProportional() {
            // 80_000 chars → 20_000 tokens → 20_000 * 0.20 = 4_000 tokens
            int budget = policy.computeSummaryBudget(80_000);
            assertThat(budget).isEqualTo(4_000);
        }
    }

    // ── Global compression count ──

    @Nested
    @DisplayName("Global compression count")
    class GlobalCompressionCount {

        @Test
        @DisplayName("Starts at 0")
        void startsAtZero() {
            assertThat(policy.getGlobalCompressionCount()).isZero();
        }

        @Test
        @DisplayName("Good savings (>= 10%) increments global count")
        void goodSavingsIncrementsGlobalCount() {
            policy.recordCompressionSavings(10_000, 5_000); // 50% → good
            assertThat(policy.getGlobalCompressionCount()).isEqualTo(1);
            policy.recordCompressionSavings(10_000, 5_000);
            assertThat(policy.getGlobalCompressionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Low savings (< 10%) does NOT increment global count")
        void lowSavingsDoesNotIncrementGlobalCount() {
            policy.recordCompressionSavings(10_000, 9_500); // 5% → low
            assertThat(policy.getGlobalCompressionCount()).isZero();
        }

        @Test
        @DisplayName("incrementGlobalCompressionCount works directly")
        void incrementDirectly() {
            policy.incrementGlobalCompressionCount();
            assertThat(policy.getGlobalCompressionCount()).isEqualTo(1);
        }
    }

    // ── Constants ──

    @Nested
    @DisplayName("Policy constants")
    class Constants {

        @Test
        @DisplayName("MINIMUM_CONTEXT_LENGTH is 64000")
        void minimumContextLength() {
            assertThat(CompressionPolicy.MINIMUM_CONTEXT_LENGTH).isEqualTo(64_000);
        }

        @Test
        @DisplayName("PROACTIVE_THRESHOLD_FRACTION is 0.50")
        void proactiveThresholdFraction() {
            assertThat(CompressionPolicy.PROACTIVE_THRESHOLD_FRACTION).isEqualTo(0.50);
        }

        @Test
        @DisplayName("COMPRESSION_THRESHOLD_FRACTION is 0.75")
        void compressionThresholdFraction() {
            assertThat(CompressionPolicy.COMPRESSION_THRESHOLD_FRACTION).isEqualTo(0.75);
        }

        @Test
        @DisplayName("LOW_SAVINGS_THRESHOLD_PCT is 10.0")
        void lowSavingsThresholdPct() {
            assertThat(CompressionPolicy.LOW_SAVINGS_THRESHOLD_PCT).isEqualTo(10.0);
        }

        @Test
        @DisplayName("MAX_CONSECUTIVE_LOW_SAVINGS is 2")
        void maxConsecutiveLowSavings() {
            assertThat(CompressionPolicy.MAX_CONSECUTIVE_LOW_SAVINGS).isEqualTo(2);
        }

        @Test
        @DisplayName("MAX_COMPRESSION_ATTEMPTS is 3")
        void maxCompressionAttempts() {
            assertThat(CompressionPolicy.MAX_COMPRESSION_ATTEMPTS).isEqualTo(3);
        }

        @Test
        @DisplayName("CHARS_PER_TOKEN is 4")
        void charsPerToken() {
            assertThat(CompressionPolicy.CHARS_PER_TOKEN).isEqualTo(4);
        }

        @Test
        @DisplayName("SUMMARY_RATIO is 0.20")
        void summaryRatio() {
            assertThat(CompressionPolicy.SUMMARY_RATIO).isEqualTo(0.20);
        }

        @Test
        @DisplayName("MIN_SUMMARY_TOKENS is 2000")
        void minSummaryTokens() {
            assertThat(CompressionPolicy.MIN_SUMMARY_TOKENS).isEqualTo(2_000);
        }

        @Test
        @DisplayName("SUMMARY_TOKENS_CEILING is 10000")
        void summaryTokensCeiling() {
            assertThat(CompressionPolicy.SUMMARY_TOKENS_CEILING).isEqualTo(10_000);
        }
    }

    @Nested
    @DisplayName("Hermes model threshold parity")
    class ModelThresholdParity {

        @Test
        void resolveModelThreshold_usesLongestMatchingOverride() {
            var thresholds = java.util.Map.of("glm-5.2", 0.60, "glm-5.2-1M", 0.85);

            assertThat(CompressionPolicy.resolveModelThreshold("glm-5.2-1M", thresholds, 0.50))
                .isEqualTo(0.85);
        }

        @Test
        void resolveModelThreshold_fallsBackWhenNoMatch() {
            assertThat(CompressionPolicy.resolveModelThreshold("gpt-5", java.util.Map.of("glm", 0.60), 0.50))
                .isEqualTo(0.50);
        }

        @Test
        void recalculateThreshold_smallContextRaisesLowOverrideToFloor() {
            policy.recalculateThreshold(128_000, "test", java.util.Map.of("test", 0.50));

            // Hermes small-context floor: max(override 0.50, floor 0.75) × 128K × 4 chars.
            assertThat(policy.getCompressionThresholdChars()).isEqualTo(128_000 * 3);
        }

        @Test
        void recalculateThreshold_smallContextKeepsHigherOverride() {
            policy.recalculateThreshold(128_000, "test", java.util.Map.of("test", 0.90));

            assertThat(policy.getCompressionThresholdChars()).isEqualTo((int) (128_000 * 0.90) * 4);
        }
    }
}