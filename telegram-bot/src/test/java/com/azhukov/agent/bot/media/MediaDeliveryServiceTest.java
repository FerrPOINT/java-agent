package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MediaDeliveryServiceTest {
    private static final String ID = "att_0123456789abcdef0123456789abcdef";
    private static final String TAG = "MEDIA:artifact:" + ID;
    private final MediaDeliveryService service = new MediaDeliveryService();

    @Test
    void extractsOnlyPublishedIdsAndPreservesDirectives() {
        var result = service.extractMediaTags("Reply\n" + TAG + "\n" + TAG + "\n[[as_document]]");
        assertThat(result.cleanedText()).isEqualTo("Reply");
        assertThat(result.media()).containsExactly(new MediaDeliveryService.MediaDescriptor(ID, false, true));
    }

    @Test
    void pathsAndExamplesCannotInitiateDelivery() {
        for (String text : java.util.List.of("MEDIA:/workspace/photo.png", "/workspace/photo.png",
                "MEDIA:/workspace/fifo", "MEDIA:/workspace/link", "example " + (char) 96 + TAG + (char) 96,
                "MEDIA:" + (char) 96 + TAG + (char) 96,
                "{\"media_tag\":\"" + TAG + "\"}")) {
            assertThat(service.extractMediaTags(text).media()).isEmpty();
        }
    }

    @Test
    void partialReferenceDoesNotAppearInPreview() {
        assertThat(service.stripMediaTagsForDisplay("Reply\nMEDIA:artifact:att_0123", true)).isEqualTo("Reply");
    }
}
