package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MediaDeliveryPartialTagTest {
    private final MediaDeliveryService service = new MediaDeliveryService();
    private static final String FIRST = "att_00000000000000000000000000000001";
    private static final String SECOND = "att_00000000000000000000000000000002";

    @Test
    void streamHidesPartialReferenceWhileFinalExtractsCompletedId() {
        String partial = "file MEDIA:artifact:" + FIRST.substring(0, 12);
        assertThat(service.stripMediaTagsForDisplay(partial, true)).isEqualTo("file");
        var result = service.extractMediaTags(partial + FIRST.substring(12));
        assertThat(result.media()).extracting(MediaDeliveryService.MediaDescriptor::artifactId).containsExactly(FIRST);
        assertThat(result.cleanedText()).isEqualTo("file");
    }

    @Test
    void completeAndPartialReferencesStayHiddenWithoutLosingText() {
        String text = "a MEDIA:artifact:" + FIRST + " b MEDIA:artifact:" + SECOND.substring(0, 14);
        assertThat(service.stripMediaTagsForDisplay(text, true)).isEqualTo("a  b");
    }
}
