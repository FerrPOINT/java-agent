package com.azhukov.agent.bot.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-11 (docs/35): artifact markers in media descriptions are extracted into
 * structured references and stripped from the visible message.
 */
class BotMessageProcessorAttachmentsTest {

    @Test
    void extractsSingleArtifactId() {
        String text = "[Photo: /tmp/media/x.jpg] (artifact=att_abc123)";
        assertThat(BotMessageProcessor.extractArtifactIds(text))
            .containsExactly("att_abc123");
    }

    @Test
    void extractsMultipleArtifactIds() {
        String text = "[Photo: /tmp/a.jpg] (artifact=att_aaa111)\n[Photo: /tmp/b.jpg] (artifact=att_bbb222)";
        assertThat(BotMessageProcessor.extractArtifactIds(text))
            .containsExactly("att_aaa111", "att_bbb222");
    }

    @Test
    void noMarkersYieldEmptyList() {
        assertThat(BotMessageProcessor.extractArtifactIds("[Photo: /tmp/x.jpg]")).isEmpty();
        assertThat(BotMessageProcessor.extractArtifactIds(null)).isEmpty();
        assertThat(BotMessageProcessor.extractArtifactIds("")).isEmpty();
    }

    @Test
    void stripsMarkersFromVisibleText() {
        String text = "[Photo: /tmp/x.jpg] (artifact=att_abc123)\nwhat is on this photo?";
        String cleaned = BotMessageProcessor.stripArtifactMarkers(text);
        assertThat(cleaned).doesNotContain("artifact=");
        assertThat(cleaned).contains("[Photo: /tmp/x.jpg]");
        assertThat(cleaned).contains("what is on this photo?");
    }

    @Test
    void stripsMultipleMarkers() {
        String text = "a (artifact=att_aaa111) b (artifact=att_bbb222) c";
        String cleaned = BotMessageProcessor.stripArtifactMarkers(text);
        assertThat(cleaned.trim()).isEqualTo("a b c");
    }

    @Test
    void nullSafeStrip() {
        assertThat(BotMessageProcessor.stripArtifactMarkers(null)).isNull();
    }

    @Test
    void ignoresNonArtifactParenthesized() {
        String text = "hello (not an artifact) world";
        assertThat(BotMessageProcessor.extractArtifactIds(text)).isEmpty();
        assertThat(BotMessageProcessor.stripArtifactMarkers(text)).isEqualTo(text);
    }
}
