package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3 fix (WP-11 tail / docs/35): a MEDIA: tag split across stream chunks
 * must not flash raw to the user — the streaming display variant holds back
 * the partial tail; the completed-text extraction still delivers it.
 */
class MediaDeliveryPartialTagTest {

    private final MediaDeliveryService service = new MediaDeliveryService();

    @Test
    void completeTagIsStrippedInBothModes() {
        String text = "before MEDIA:/tmp/cat.png after";
        assertThat(service.stripMediaTagsForDisplay(text, false)).isEqualTo("before  after");
        assertThat(service.stripMediaTagsForDisplay(text, true)).isEqualTo("before  after");
    }

    @Test
    void partialTagTailIsHeldBackInStreamingMode() {
        // path still arriving: no closing extension yet
        String partial = "here is the file MEDIA:/tmp/ima";
        String streamed = service.stripMediaTagsForDisplay(partial, true);
        assertThat(streamed).doesNotContain("MEDIA:");
        assertThat(streamed).doesNotContain("/tmp/ima");
        assertThat(streamed.trim()).isEqualTo("here is the file");
    }

    @Test
    void partialTagFlashesInFinalModeButExtractionStillCatchesCompletion() {
        // Non-streaming (final) display keeps legacy behavior; the extraction
        // pass over COMPLETED text is what delivers the file.
        String partial = "here is the file MEDIA:/tmp/ima";
        // completed continuation
        String completed = partial + "ge.png";
        assertThat(service.stripMediaTagsForDisplay(completed, false))
            .isEqualTo("here is the file");
        MediaDeliveryService.ExtractionResult extraction = service.extractMediaTags(completed);
        assertThat(extraction.media()).hasSize(1);
        assertThat(extraction.media().get(0).path()).isEqualTo("/tmp/image.png");
    }

    @Test
    void earlierCompleteTagIsStrippedWhileLaterPartialIsHeldBack() {
        String text = "a MEDIA:/tmp/one.png b MEDIA:/tmp/tw";
        String streamed = service.stripMediaTagsForDisplay(text, true);
        // the complete first tag is removed entirely
        assertThat(streamed).doesNotContain("one.png");
        // the partial second tag is held back
        assertThat(streamed).doesNotContain("MEDIA:");
        assertThat(streamed).doesNotContain("/tmp/tw");
        assertThat(streamed).contains("b");
    }

    @Test
    void directivesStillStrippedInStreamingMode() {
        String text = "voice [[audio_as_voice]] MEDIA:/tmp/re";
        String streamed = service.stripMediaTagsForDisplay(text, true);
        assertThat(streamed).doesNotContain("[[audio_as_voice]]");
        assertThat(streamed).doesNotContain("MEDIA:");
    }

    @Test
    void mediaInsideQuotesHeldBackWhenPartial() {
        String partial = "file `MEDIA:/tmp/report";
        String streamed = service.stripMediaTagsForDisplay(partial, true);
        assertThat(streamed).doesNotContain("MEDIA:");
    }
}
