package com.azhukov.agent.bot.cron;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6: Hermes gateway/response_filters.py is_autonomous_silence_response —
 * exact semantics matrix (whole-token / first-last-line / bracketed prefix /
 * mid-sentence NOT suppressed).
 */
class AutonomousSilenceFilterTest {

    @Test
    void wholeResponseExactlyAMarkerIsSilence() {
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("[SILENT]")).isTrue();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("SILENT")).isTrue();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("NO_REPLY")).isTrue();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("NO REPLY")).isTrue();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("  no_reply  ")).isTrue(); // canonicalised
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("[silent]")).isTrue();
    }

    @Test
    void markerOnOwnFirstOrLastLineIsSilence() {
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("2 deals filtered\n\n[SILENT]")).isTrue();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("[SILENT]\n\nbecause nothing changed")).isTrue();
    }

    @Test
    void bracketedPrefixIsSilence() {
        // The documented pattern
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("[SILENT] No changes detected")).isTrue();
    }

    @Test
    void markerBuriedMidSentenceIsDelivered() {
        assertThat(AutonomousSilenceFilter.isAutonomousSilence(
            "Report: all good. The word NO_REPLY appears here mid-sentence")).isFalse();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence(
            "Silent retry succeeded")).isFalse(); // bare word, not bracketed
        assertThat(AutonomousSilenceFilter.isAutonomousSilence(
            "Normal report\nwith several lines\nand real content")).isFalse();
    }

    @Test
    void nullAndBlankAreNotSilence() {
        assertThat(AutonomousSilenceFilter.isAutonomousSilence(null)).isFalse();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("")).isFalse();
        assertThat(AutonomousSilenceFilter.isAutonomousSilence("   ")).isFalse();
    }
}
