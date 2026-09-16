package com.azhukov.agent.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1: shared autonomous-silence matcher (Hermes
 * gateway/response_filters.py is_autonomous_silence_response). The producer
 * gate must suppress exactly the same shapes the Hermes gateway suppresses.
 */
class CronSilenceFilterTest {

    @Test
    void wholeResponseMarkerIsSilent() {
        assertThat(CronSilenceFilter.isSilent("[SILENT]")).isTrue();
        assertThat(CronSilenceFilter.isSilent("SILENT")).isTrue();
        assertThat(CronSilenceFilter.isSilent("NO_REPLY")).isTrue();
        assertThat(CronSilenceFilter.isSilent("no reply")).isTrue();
    }

    @Test
    void markerOnOwnFirstOrLastLineIsSilent() {
        assertThat(CronSilenceFilter.isSilent("[SILENT]\nNo changes detected.")).isTrue();
        assertThat(CronSilenceFilter.isSilent("Report complete.\n\nSILENT")).isTrue();
    }

    @Test
    void bracketedSentinelPrefixIsSilent() {
        assertThat(CronSilenceFilter.isSilent("[SILENT] No changes detected")).isTrue();
    }

    @Test
    void blankOrNullIsSilent() {
        assertThat(CronSilenceFilter.isSilent(null)).isTrue();
        assertThat(CronSilenceFilter.isSilent("")).isTrue();
        assertThat(CronSilenceFilter.isSilent("   \n  ")).isTrue();
    }

    @Test
    void genuineReportIsNotSilent() {
        assertThat(CronSilenceFilter.isSilent("Daily report: 3 PRs reviewed, 1 merged.")).isFalse();
        // Marker buried mid-sentence in a genuine report still delivers.
        assertThat(CronSilenceFilter.isSilent("I considered staying [SILENT] but here is the summary of changes.")).isFalse();
    }

    @Test
    void silenceMarkerMidReportDelivers() {
        String report = "Header line\nsome [SILENT]-adjacent middle line\nfooter";
        assertThat(CronSilenceFilter.isSilent(report)).isFalse();
    }
}
