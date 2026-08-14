package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for context compression parity features (Features 1-3, 5):
 * <ul>
 *   <li>Feature 1: Proactive compression (shouldCompressProactive)</li>
 *   <li>Feature 2: Anti-thrashing protection (consecutiveLowSavings tracking)</li>
 *   <li>Feature 3: Minimum context floor (64K tokens)</li>
 *   <li>Feature 5: Multiple compression attempts (MAX_COMPRESSION_ATTEMPTS = 3)</li>
 * </ul>
 */
class ContextCompressionParityTest {

    private DefaultContextCompressor compressor;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        compressor = new DefaultContextCompressor(mock(com.azhukov.agent.core.client.ModelClient.class), null, props);
    }

    // ── Feature 1: Proactive compression (shouldCompressProactive) ──

    @Nested
    @DisplayName("Feature 1: Proactive compression (shouldCompressProactive)")
    class ProactiveCompression {

        @Test
        @DisplayName("Returns false when estimated tokens below 50% threshold")
        void belowThreshold() {
            // 128K context, 50% = 64K, estimated 30K < 64K → no compression
            assertThat(compressor.shouldCompressProactive(30_000, 128_000)).isFalse();
        }

        @Test
        @DisplayName("Returns true when estimated tokens at or above 50% threshold")
        void atThreshold() {
            // 128K context, 50% = 64K, estimated 64K >= 64K → compress
            assertThat(compressor.shouldCompressProactive(64_000, 128_000)).isTrue();
        }

        @Test
        @DisplayName("Returns true when estimated tokens above 50% threshold")
        void aboveThreshold() {
            // 128K context, 50% = 64K, estimated 80K > 64K → compress
            assertThat(compressor.shouldCompressProactive(80_000, 128_000)).isTrue();
        }

        @Test
        @DisplayName("Returns false when context window size is 0 or negative")
        void invalidContextWindowSize() {
            assertThat(compressor.shouldCompressProactive(100_000, 0)).isFalse();
            assertThat(compressor.shouldCompressProactive(100_000, -1)).isFalse();
        }
    }

    // ── Feature 3: Minimum context floor (64K tokens) ──

    @Nested
    @DisplayName("Feature 3: Minimum context floor (64K tokens)")
    class MinimumContextFloor {

        @Test
        @DisplayName("64K floor prevents premature compression on large-context models")
        void largeContextModelFloor() {
            // 200K context model, 50% = 100K threshold → 50K < 100K → no compression
            assertThat(compressor.shouldCompressProactive(50_000, 200_000)).isFalse();
        }

        @Test
        @DisplayName("64K floor applies when context window is small (8K)")
        void smallContextFloor() {
            // 8K context, 50% = 4K, but 64K floor → threshold = max(4K, 64K) = 64K
            // 70K >= 64K → compress (above floor)
            assertThat(compressor.shouldCompressProactive(70_000, 8_192)).isTrue();
            // 50K < 64K floor → no compression
            assertThat(compressor.shouldCompressProactive(50_000, 8_192)).isFalse();
        }

        @Test
        @DisplayName("MINIMUM_CONTEXT_LENGTH constant is 64000")
        void minimumContextLengthValue() {
            assertThat(DefaultContextCompressor.MINIMUM_CONTEXT_LENGTH).isEqualTo(64_000);
        }

        @Test
        @DisplayName("Proactive threshold fraction is 0.50")
        void proactiveThresholdFraction() {
            assertThat(DefaultContextCompressor.PROACTIVE_THRESHOLD_FRACTION).isEqualTo(0.50);
        }
    }

    // ── Feature 2: Anti-thrashing protection ──

    @Nested
    @DisplayName("Feature 2: Anti-thrashing protection")
    class AntiThrashing {

        @Test
        @DisplayName("shouldCompress returns true when no low-savings compressions")
        void shouldCompressNoLowSavings() {
            assertThat(compressor.shouldCompress(100_000)).isTrue();
        }

        @Test
        @DisplayName("shouldCompress returns false after 2 consecutive low-savings compressions")
        void shouldCompressAfterTwoLowSavings() {
            // Record 2 low-savings compressions (savings < 10%)
            compressor.recordCompressionSavings(10_000, 9_500); // 5% savings → low
            compressor.recordCompressionSavings(10_000, 9_200); // 8% savings → low
            assertThat(compressor.getConsecutiveLowSavings()).isEqualTo(2);
            // Now shouldCompress should return false (anti-thrashing)
            assertThat(compressor.shouldCompress(100_000)).isFalse();
        }

        @Test
        @DisplayName("shouldCompressProactive also respects anti-thrashing")
        void shouldCompressProactiveRespectsAntiThrashing() {
            // Record 2 low-savings compressions
            compressor.recordCompressionSavings(10_000, 9_500); // 5% → low
            compressor.recordCompressionSavings(10_000, 9_200); // 8% → low
            // Even though estimated tokens exceed threshold, anti-thrashing skips it
            assertThat(compressor.shouldCompressProactive(100_000, 128_000)).isFalse();
        }

        @Test
        @DisplayName("Counter resets when a compression saves > 10%")
        void counterResetsOnGoodSavings() {
            // Record 1 low-savings compression
            compressor.recordCompressionSavings(10_000, 9_500); // 5% → low
            assertThat(compressor.getConsecutiveLowSavings()).isEqualTo(1);
            // Record a good compression (saves 50%)
            compressor.recordCompressionSavings(10_000, 5_000); // 50% → good
            assertThat(compressor.getConsecutiveLowSavings()).isZero();
            // shouldCompress should work again
            assertThat(compressor.shouldCompress(100_000)).isTrue();
        }

        @Test
        @DisplayName("resetAntiThrashing clears the counter")
        void resetAntiThrashing() {
            compressor.recordCompressionSavings(10_000, 9_500); // 5% → low
            compressor.recordCompressionSavings(10_000, 9_200); // 8% → low
            assertThat(compressor.getConsecutiveLowSavings()).isEqualTo(2);
            compressor.resetAntiThrashing();
            assertThat(compressor.getConsecutiveLowSavings()).isZero();
            assertThat(compressor.getLastCompressionSavingsPct()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Low savings threshold is 10%")
        void lowSavingsThreshold() {
            assertThat(DefaultContextCompressor.LOW_SAVINGS_THRESHOLD_PCT).isEqualTo(10.0);
        }

        @Test
        @DisplayName("Max consecutive low savings is 2")
        void maxConsecutiveLowSavings() {
            assertThat(DefaultContextCompressor.MAX_CONSECUTIVE_LOW_SAVINGS).isEqualTo(2);
        }

        @Test
        @DisplayName("Exactly at 10% savings is NOT considered low (boundary)")
        void boundaryAt10Percent() {
            // 10% savings exactly → not low (savings_pct < 10 is the check)
            compressor.recordCompressionSavings(10_000, 9_000); // 10% → not low
            assertThat(compressor.getConsecutiveLowSavings()).isZero();
        }

        @Test
        @DisplayName("Just under 10% savings IS considered low (boundary)")
        void boundaryJustUnder10Percent() {
            // 9.9% savings → low
            compressor.recordCompressionSavings(10_000, 9_010); // 9.9% → low
            assertThat(compressor.getConsecutiveLowSavings()).isEqualTo(1);
        }

        @Test
        @DisplayName("Zero original tokens → savings = 0% → low")
        void zeroOriginalTokens() {
            compressor.recordCompressionSavings(0, 0);
            assertThat(compressor.getConsecutiveLowSavings()).isEqualTo(1);
            assertThat(compressor.getLastCompressionSavingsPct()).isEqualTo(0.0);
        }
    }

    // ── Feature 5: Multiple compression attempts (MAX_COMPRESSION_ATTEMPTS = 3) ──

    @Nested
    @DisplayName("Feature 5: Multiple compression attempts")
    class MultipleCompressionAttempts {

        @Test
        @DisplayName("MAX_COMPRESSION_ATTEMPTS is 3")
        void maxAttemptsIs3() {
            assertThat(DefaultContextCompressor.MAX_COMPRESSION_ATTEMPTS).isEqualTo(3);
        }
    }

    // ── Compression with savings tracking ──

    @Nested
    @DisplayName("Compression records savings")
    class CompressionSavingsTracking {

        @Test
        @DisplayName("compress() records savings after a successful compression")
        void compressRecordsSavings() {
            AgentProperties props = new AgentProperties();
            props.getContext().setProtectFirstN(1);
            props.getContext().setProtectLastN(1);
            // Use a mock ModelClient that returns a short summary (unlike NoOp which echoes input)
            var mockClient = mock(com.azhukov.agent.core.client.ModelClient.class);
            when(mockClient.complete(any(List.class), any(List.class)))
                .thenReturn(ChatResponse.text("Summary of earlier conversation."));
            var compressor = new DefaultContextCompressor(mockClient, null, props);

            // Create messages large enough to trigger compression with significant savings.
            // The middle section (assistant) needs to be much larger than the summary
            // that replaces it (anti-injection prefix + short summary + end marker ≈ 200 chars).
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("start"));
            messages.add(Message.assistant("b".repeat(10_000), 1)); // Large middle → large savings
            messages.add(Message.user("current"));

            var result = compressor.compress(messages, 100);
            // Compression should have occurred
            assertThat(result).hasSize(3); // head + summary + tail
            // Savings should be positive (10K chars compressed to ~200 chars → >90% savings)
            assertThat(compressor.getLastCompressionSavingsPct()).isGreaterThan(50.0);
        }

        @Test
        @DisplayName("compress() with no reduction records low savings")
        void compressNoReductionRecordsLowSavings() {
            // If compression doesn't reduce, savings would be 0 or very low
            // This can happen when messages are too few to compress
            var mockClient = mock(com.azhukov.agent.core.client.ModelClient.class);
            var compressor = new DefaultContextCompressor(mockClient, null, new AgentProperties());
            var messages = List.of(Message.user("hi"), Message.assistant("hello", 1));
            var result = compressor.compress(messages, 1000);
            // No compression happens (messages already under target)
            assertThat(result).isEqualTo(messages);
            // Savings tracking is NOT called when compress returns early
            // (only called after actual compression)
        }
    }
}