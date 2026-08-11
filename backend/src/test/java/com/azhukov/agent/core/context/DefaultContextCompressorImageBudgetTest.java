package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for image-token estimation in the compression budget and the
 * simplified compression-session metadata (compression boundary logging).
 * <p>
 * Mirrors the Hermes test {@code test_compressor_image_tokens.py} and the
 * session-rotation gap described in {@code conversation_compression.py}.
 */
class DefaultContextCompressorImageBudgetTest {

    private static final int IMAGE_CHAR_EQUIV = DefaultContextCompressor.IMAGE_CHAR_EQUIVALENT;

    private DefaultContextCompressor compressorWithModel(ModelClient model) {
        AgentProperties props = new AgentProperties();
        props.getContext().setMaxTokens(16000);
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        return new DefaultContextCompressor(model, null, props);
    }

    private ModelClient mockModelReturning(String summary) {
        ModelClient model = mock(ModelClient.class);
        when(model.complete(any(), any())).thenReturn(ChatResponse.text(summary));
        return model;
    }

    // ─── Image token estimate constants ───

    @Nested
    @DisplayName("Image token estimate constants")
    class ImageTokenConstants {
        @Test
        @DisplayName("IMAGE_CHAR_EQUIVALENT is IMAGE_TOKEN_ESTIMATE * CHARS_PER_TOKEN (= 1600 * 4 = 6400)")
        void imageCharEquivalentIsCorrect() {
            // Hermes: _IMAGE_CHAR_EQUIVALENT = _IMAGE_TOKEN_ESTIMATE * _CHARS_PER_TOKEN
            assertThat(IMAGE_CHAR_EQUIV).isEqualTo(1600 * 4);
            assertThat(IMAGE_CHAR_EQUIV).isEqualTo(6400);
        }

        @Test
        @DisplayName("IMAGE_TOKEN_ESTIMATE is in a reasonable range (800–2500)")
        void imageTokenEstimateIsReasonable() {
            // Hermes test asserts 800 <= _IMAGE_TOKEN_ESTIMATE <= 2500
            int tokenEstimate = IMAGE_CHAR_EQUIV / 4;
            assertThat(tokenEstimate).isBetween(800, 2500);
        }
    }

    // ─── contentLengthForBudget ───

    @Nested
    @DisplayName("contentLengthForBudget: text + image accounting")
    class ContentLengthForBudget {

        @Test
        @DisplayName("Plain text message: budget = text length")
        void plainTextBudgetIsTextLength() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.user("hello world");
            assertThat(compressor.contentLengthForBudget(m)).isEqualTo(11);
        }

        @Test
        @DisplayName("Null content: budget = 0")
        void nullContentBudgetIsZero() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.assistantToolCalls(List.of(), 1); // null content
            assertThat(compressor.contentLengthForBudget(m)).isZero();
        }

        @Test
        @DisplayName("Message with 1 image: budget = text + IMAGE_CHAR_EQUIVALENT")
        void singleImageBudget() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.userWithImages("look", 1);
            assertThat(compressor.contentLengthForBudget(m)).isEqualTo(4 + IMAGE_CHAR_EQUIV);
        }

        @Test
        @DisplayName("Message with 3 images: budget = text + 3 * IMAGE_CHAR_EQUIVALENT")
        void multipleImagesBudget() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.userWithImages("compare", 3);
            assertThat(compressor.contentLengthForBudget(m)).isEqualTo(7 + 3 * IMAGE_CHAR_EQUIV);
        }

        @Test
        @DisplayName("Message with 0 images (default): budget = text length only")
        void zeroImagesBudgetIsTextOnly() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.user("hello");
            assertThat(compressor.contentLengthForBudget(m)).isEqualTo(5);
        }

        @Test
        @DisplayName("Image-only message with empty text: budget = IMAGE_CHAR_EQUIVALENT")
        void imageOnlyEmptyTextBudget() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            Message m = Message.userWithImages("", 1);
            assertThat(compressor.contentLengthForBudget(m)).isEqualTo(IMAGE_CHAR_EQUIV);
        }
    }

    // ─── Image budget affects threshold check ───

    @Nested
    @DisplayName("Image budget affects compression threshold")
    class ImageThresholdEffect {

        @Test
        @DisplayName("Messages with images trigger compression even when text is short")
        void imagesTriggerCompressionWithShortText() {
            ModelClient model = mockModelReturning("summary");
            DefaultContextCompressor compressor = compressorWithModel(model);

            // 3 messages: short text + one with an image.
            // Without image budgeting, total text ~30 chars, would NOT exceed target.
            // With image budgeting, 1 image = 6400 chars, exceeds target of 100.
            List<Message> messages = List.of(
                Message.user("a".repeat(20)),
                Message.userWithImages("b".repeat(10), 1),
                Message.user("current")
            );

            List<Message> result = compressor.compress(messages, 100);

            // Should have compressed (head + summary + tail)
            assertThat(result).hasSize(3);
            assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        }

        @Test
        @DisplayName("Messages with many images blow past target even with tiny text")
        void manyImagesBlowPastTarget() {
            ModelClient model = mockModelReturning("summary");
            DefaultContextCompressor compressor = compressorWithModel(model);

            // 5 images = 5 * 6400 = 32000 chars equivalent, far exceeding any reasonable target
            List<Message> messages = List.of(
                Message.user("head"),
                Message.userWithImages("", 5),
                Message.user("tail")
            );

            List<Message> result = compressor.compress(messages, 10_000);

            // Should have compressed
            assertThat(result).hasSize(3);
            assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        }

        @Test
        @DisplayName("Short text without images stays under target (no compression)")
        void noImagesShortTextNoCompression() {
            ModelClient model = mockModelReturning("summary");
            DefaultContextCompressor compressor = compressorWithModel(model);

            List<Message> messages = List.of(
                Message.user("a"),
                Message.assistant("b", 1),
                Message.user("c")
            );

            List<Message> result = compressor.compress(messages, 1000);

            // Should NOT compress — under threshold
            assertThat(result).isSameAs(messages);
        }
    }

    // ─── Compression boundary logging (simplified session metadata) ───

    @Nested
    @DisplayName("Compression boundary logging")
    class CompressionBoundary {

        @Test
        @DisplayName("logCompressionBoundary invokes callback with a timestamp")
        void logCompressionBoundaryInvokesCallback() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            AtomicReference<Instant> captured = new AtomicReference<>();

            compressor.logCompressionBoundary("session-123", captured::set);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get()).isBeforeOrEqualTo(Instant.now());
        }

        @Test
        @DisplayName("logCompressionBoundary handles null sessionId gracefully")
        void logCompressionBoundaryNullSessionId() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));
            AtomicReference<Instant> captured = new AtomicReference<>();

            // Should not throw
            compressor.logCompressionBoundary(null, captured::set);

            assertThat(captured.get()).isNotNull();
        }

        @Test
        @DisplayName("logCompressionBoundary handles null callback gracefully")
        void logCompressionBoundaryNullCallback() {
            DefaultContextCompressor compressor = compressorWithModel(mockModelReturning("s"));

            // Should not throw
            compressor.logCompressionBoundary("session-456", null);
        }
    }

    // ─── withImageCount factory method ───

    @Nested
    @DisplayName("Message.withImageCount")
    class WithImageCount {
        @Test
        @DisplayName("withImageCount creates a new message with updated image count, preserving other fields")
        void withImageCountPreservesFields() {
            Message original = Message.user("hello");
            Message withImages = Message.withImageCount(original, 3);

            assertThat(withImages.imageCount()).isEqualTo(3);
            assertThat(withImages.content()).isEqualTo("hello");
            assertThat(withImages.role()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("Default imageCount is 0 for all standard factory methods")
        void defaultImageCountIsZero() {
            assertThat(Message.user("hi").imageCount()).isZero();
            assertThat(Message.system("sys").imageCount()).isZero();
            assertThat(Message.assistant("a", 1).imageCount()).isZero();
            assertThat(Message.toolResult("id", "content", 1).imageCount()).isZero();
            assertThat(Message.developer("dev").imageCount()).isZero();
        }

        @Test
        @DisplayName("userWithImages creates a message with the specified image count")
        void userWithImagesSetsCount() {
            assertThat(Message.userWithImages("hello", 2).imageCount()).isEqualTo(2);
            assertThat(Message.userWithImages("hello", 0).imageCount()).isZero();
        }
    }
}