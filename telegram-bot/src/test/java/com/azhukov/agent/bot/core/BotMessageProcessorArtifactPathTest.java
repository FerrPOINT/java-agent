package com.azhukov.agent.bot.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-11 (docs/35): outbound artifact ids resolve from cache paths and drive
 * duplicate-send suppression.
 */
class BotMessageProcessorArtifactPathTest {

    @Test
    void resolvesArtifactIdFromCachePath() {
        assertThat(BotMessageProcessor.artifactIdForPath(
            "/tmp/agent-attachments/12345/att_a1b2c3d4e5f67890a1b2c3d4e5f67890.png"))
            .isEqualTo("att_a1b2c3d4e5f67890a1b2c3d4e5f67890");
    }

    @Test
    void resolvesArtifactIdFromLegacyMediaPath() {
        assertThat(BotMessageProcessor.artifactIdForPath("/tmp/agent-media/att_abc123.jpg"))
            .isEqualTo("att_abc123");
    }

    @Test
    void plainPathYieldsNoArtifactId() {
        assertThat(BotMessageProcessor.artifactIdForPath("/tmp/agent-media/photo_2026.jpg")).isNull();
        assertThat(BotMessageProcessor.artifactIdForPath("/tmp/report.pdf")).isNull();
    }

    @Test
    void noExtensionYieldsNoArtifactId() {
        assertThat(BotMessageProcessor.artifactIdForPath("/tmp/x/att_abc123")).isNull();
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(BotMessageProcessor.artifactIdForPath(null)).isNull();
        assertThat(BotMessageProcessor.artifactIdForPath("")).isNull();
    }

    @Test
    void onlyHexSuffixAccepted() {
        // att_ prefix + hex only — a random filename like "pattern_att_zzz.png"
        // (zzz not hex) must not resolve
        assertThat(BotMessageProcessor.artifactIdForPath("/tmp/pattern_att_zzz.png")).isNull();
    }
}
