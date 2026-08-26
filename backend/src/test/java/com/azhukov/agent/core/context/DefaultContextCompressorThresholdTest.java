package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P2-51: Tests for dynamic compression threshold recalculation when the model switches.
 * <p>
 * Verifies that {@link DefaultContextCompressor#recalculateThreshold(int)} correctly
 * updates the compression threshold based on the new model's context window size,
 * and that the threshold is derived as 75% of the context window (in tokens) converted
 * to chars (× 4 chars/token).
 */
class DefaultContextCompressorThresholdTest {

    private DefaultContextCompressor createCompressor() {
        AgentProperties props = new AgentProperties();
        props.getContext().setMaxTokens(16000);
        return new DefaultContextCompressor(mock(ModelClient.class), null, props);
    }

    @Nested
    @DisplayName("recalculateThreshold")
    class RecalculateThreshold {

        @Test
        @DisplayName("Threshold is 0 before recalculateThreshold is called")
        void thresholdIsZeroByDefault() {
            DefaultContextCompressor compressor = createCompressor();
            assertThat(compressor.getCompressionThresholdChars()).isZero();
        }

        @Test
        @DisplayName("Threshold is recalculated as 75% of context window × 4 chars/token")
        void thresholdRecalculatedCorrectly() {
            // 128K < 512K → small-context floor raises 0.50 default to 0.75 (context_compressor.py:3056)
            DefaultContextCompressor compressor = createCompressor();
            compressor.recalculateThreshold(131_072);
            int expected = (int) (131_072 * CompressionPolicy.SMALL_CTX_THRESHOLD_PERCENT) * 4;
            assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Threshold for a small context window (8K) uses reachable 85% trigger")
        void smallContextWindow() {
            DefaultContextCompressor compressor = createCompressor();
            compressor.recalculateThreshold(8_192);
            int expected = (int) (8_192 * CompressionPolicy.MIN_CTX_TRIGGER_RATIO) * 4;
            assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Threshold for a large context window (200K)")
        void largeContextWindow() {
            // 200K < 512K → small-context floor: 0.75 × 200000 → × 4 chars
            DefaultContextCompressor compressor = createCompressor();
            compressor.recalculateThreshold(200_000);
            int expected = (int) (200_000 * CompressionPolicy.SMALL_CTX_THRESHOLD_PERCENT) * 4;
            assertThat(compressor.getCompressionThresholdChars()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Threshold is updated when model switches to a different context window")
        void thresholdUpdatedOnModelSwitch() {
            DefaultContextCompressor compressor = createCompressor();

            // First model: 32K context → min floor would fill the window, so 85% reachable trigger.
            compressor.recalculateThreshold(32_768);
            int firstThreshold = compressor.getCompressionThresholdChars();
            assertThat(firstThreshold).isEqualTo((int) (32_768 * CompressionPolicy.MIN_CTX_TRIGGER_RATIO) * 4);

            // Switch to 128K < 512K → floor keeps 75%
            compressor.recalculateThreshold(131_072);
            int secondThreshold = compressor.getCompressionThresholdChars();
            assertThat(secondThreshold).isEqualTo((int) (131_072 * CompressionPolicy.SMALL_CTX_THRESHOLD_PERCENT) * 4);
            assertThat(secondThreshold).isGreaterThan(firstThreshold);

            // Switch to a model with 8K context — use 85% reachable trigger.
            compressor.recalculateThreshold(8_192);
            int thirdThreshold = compressor.getCompressionThresholdChars();
            assertThat(thirdThreshold).isEqualTo((int) (8_192 * CompressionPolicy.MIN_CTX_TRIGGER_RATIO) * 4);
            assertThat(thirdThreshold).isLessThan(secondThreshold);
        }

        @Test
        @DisplayName("Non-positive context window size is ignored")
        void nonPositiveContextWindowIgnored() {
            DefaultContextCompressor compressor = createCompressor();
            compressor.recalculateThreshold(131_072);
            int originalThreshold = compressor.getCompressionThresholdChars();
            assertThat(originalThreshold).isGreaterThan(0);

            // Zero should be ignored
            compressor.recalculateThreshold(0);
            assertThat(compressor.getCompressionThresholdChars()).isEqualTo(originalThreshold);

            // Negative should be ignored
            compressor.recalculateThreshold(-1000);
            assertThat(compressor.getCompressionThresholdChars()).isEqualTo(originalThreshold);
        }

        @Test
        @DisplayName("Threshold ratio is 75% of context window")
        void thresholdRatioIs75Percent() {
            DefaultContextCompressor compressor = createCompressor();
            int contextWindow = 100_000;
            compressor.recalculateThreshold(contextWindow);

            // thresholdTokens = 75000, thresholdChars = 300000
            // Verify the ratio: <512K window → 75% (small-context floor over the 0.50 default)
            int thresholdChars = compressor.getCompressionThresholdChars();
            int contextChars = contextWindow * 4;
            double ratio = (double) thresholdChars / contextChars;
            assertThat(ratio).isEqualTo(CompressionPolicy.SMALL_CTX_THRESHOLD_PERCENT, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Nested
    @DisplayName("Interface default method")
    class InterfaceDefaultMethod {

        @Test
        @DisplayName("ContextCompressor interface has recalculateThreshold default method")
        void interfaceHasDefaultMethod() {
            // The default implementation is a no-op, so calling it on a mock should not throw
            ContextCompressor compressor = mock(ContextCompressor.class);
            // Mock will record the call; the real default is a no-op
            compressor.recalculateThreshold(100_000);
            // No exception thrown — default method exists
        }
    }
}