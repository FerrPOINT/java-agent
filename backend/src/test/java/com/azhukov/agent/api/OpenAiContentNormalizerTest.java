package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiContentNormalizerTest {

    @Test
    void inputImageIsAcceptedAsVisibleConversationPayload() {
        OpenAiContentNormalizer.NormalizedConversationContent normalized =
            OpenAiContentNormalizer.normalizeConversationContent(List.of(
                Map.of("type", "input_text", "text", "Describe."),
                Map.of("type", "input_image", "image_url", "https://example.com/cat.png")
            ));

        assertThat(normalized.text()).isEqualTo("Describe.\n[image_url: https://example.com/cat.png]");
        assertThat(normalized.imageCount()).isEqualTo(1);
        assertThat(normalized.hasVisiblePayload()).isTrue();
    }

    @Test
    void imageOnlyListIsVisiblePayload() {
        OpenAiContentNormalizer.NormalizedConversationContent normalized =
            OpenAiContentNormalizer.normalizeConversationContent(List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAAA"))
            ));

        assertThat(normalized.text()).isEqualTo("[image_url: data:image/png;base64,<redacted>]");
        assertThat(normalized.text()).doesNotContain("AAAA");
        assertThat(normalized.imageCount()).isEqualTo(1);
        assertThat(normalized.hasVisiblePayload()).isTrue();
    }

    @Test
    void invalidImageUrlIsRejected() {
        assertThatThrownBy(() -> OpenAiContentNormalizer.normalizeConversationContent(List.of(
            Map.of("type", "input_image", "image_url", "ftp://example.com/cat.png")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Image inputs must use http(s) URLs or data:image/... URLs.");
    }

    @Test
    void inputFileIsRejectedWithHermesLikeMessage() {
        assertThatThrownBy(() -> OpenAiContentNormalizer.normalizeConversationContent(List.of(
            Map.of("type", "input_file", "file_id", "file_123")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Inline image inputs are supported, but uploaded files and document inputs are not supported on this endpoint.");
    }
}
