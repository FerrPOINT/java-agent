package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link SilenceMarkerUtils} — extracted from StreamEditor.
 * Hermes parity: response_filters.py is_intentional_silence_response.
 */
class SilenceMarkerUtilsTest {

    // ─── isSilenceMarker tests ───────────────────────────────────

    @Test
    void exactMarkersSuppressed() {
        assertThat(SilenceMarkerUtils.isSilenceMarker("NO_REPLY")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("[SILENT]")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("SILENT")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("NO REPLY")).isTrue();
    }

    @Test
    void canonicalisation() {
        assertThat(SilenceMarkerUtils.isSilenceMarker("  no_reply  ")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("no reply")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("[silent]")).isTrue();
    }

    @Test
    void edgePunctuationStripped() {
        assertThat(SilenceMarkerUtils.isSilenceMarker(".NO_REPLY")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("*NO_REPLY*")).isTrue();
        assertThat(SilenceMarkerUtils.isSilenceMarker("[SILENT].")).isTrue();
    }

    @Test
    void bracketsStayStructural() {
        // "[SILENT" must NOT become "SILENT" via punctuation stripping of '['
        assertThat(SilenceMarkerUtils.isSilenceMarker("[SILENT")).isFalse();
    }

    @Test
    void proseNeverSuppressed() {
        assertThat(SilenceMarkerUtils.isSilenceMarker("NO_REPLY is the marker the gateway suppresses")).isFalse();
        assertThat(SilenceMarkerUtils.isSilenceMarker("The system stays SILENT when idle")).isFalse();
        assertThat(SilenceMarkerUtils.isSilenceMarker(
            "I will not reply to that. NO_REPLY said the docs, and the model obeyed the rule about markers in long prose."))
            .isFalse();
    }

    @Test
    void lengthCap() {
        String longText = "NO_REPLY ".repeat(20).strip();
        assertThat(longText.length()).isGreaterThan(64);
        assertThat(SilenceMarkerUtils.isSilenceMarker(longText)).isFalse();
    }

    @Test
    void blankIsNotSilence() {
        assertThat(SilenceMarkerUtils.isSilenceMarker("")).isFalse();
        assertThat(SilenceMarkerUtils.isSilenceMarker("   ")).isFalse();
        assertThat(SilenceMarkerUtils.isSilenceMarker(null)).isFalse();
    }

    @Test
    void legacyTripleStarNotSilence() {
        // *** was in the old java set; Hermes does not have it
        assertThat(SilenceMarkerUtils.isSilenceMarker("***")).isFalse();
    }

    // ─── endsWithPartialSilenceMarker tests ──────────────────────

    @Test
    void partialPrefixesHeldBack() {
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("NO")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("NO_")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("NO_REPL")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("[SILE")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("SILE")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("SILEN")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("NO REP")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("NO REPLY")).isTrue();
    }

    @Test
    void normalTextNotHeldBack() {
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("Here is the answer: 42")).isFalse();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("Команда выполнена")).isFalse();
    }

    @Test
    void blankAndNullNotPartial() {
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker(null)).isFalse();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("")).isFalse();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("   ")).isFalse();
    }

    @Test
    void tripleStarAndDoubleStarHeldBack() {
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("***")).isTrue();
        assertThat(SilenceMarkerUtils.endsWithPartialSilenceMarker("**")).isTrue();
    }

    // ─── canonicalSilence tests ──────────────────────────────────

    @Test
    void canonicalSilence_uppercasesAndCollapsesWhitespace() {
        assertThat(SilenceMarkerUtils.canonicalSilence("  no  reply  ")).isEqualTo("NO REPLY");
        assertThat(SilenceMarkerUtils.canonicalSilence("no_reply")).isEqualTo("NO_REPLY");
    }

    // ─── stripEdgeSilencePunctuation tests ───────────────────────

    @Test
    void stripEdgeSilencePunctuation_stripsPeriods() {
        assertThat(SilenceMarkerUtils.stripEdgeSilencePunctuation(".NO_REPLY.")).isEqualTo("NO_REPLY");
    }

    @Test
    void stripEdgeSilencePunctuation_stripsAsterisks() {
        assertThat(SilenceMarkerUtils.stripEdgeSilencePunctuation("*NO_REPLY*")).isEqualTo("NO_REPLY");
    }

    @Test
    void stripEdgeSilencePunctuation_keepsBrackets() {
        // Brackets should stay — only non-bracket punctuation is stripped
        assertThat(SilenceMarkerUtils.stripEdgeSilencePunctuation("[SILENT]")).isEqualTo("[SILENT]");
    }

    @Test
    void stripEdgeSilencePunctuation_stripsTrailingPeriodAfterBracket() {
        assertThat(SilenceMarkerUtils.stripEdgeSilencePunctuation("[SILENT].")).isEqualTo("[SILENT]");
    }

    // ─── isPunctuation tests ─────────────────────────────────────

    @Test
    void isPunctuation_periodsAndAsterisks() {
        assertThat(SilenceMarkerUtils.isPunctuation('.')).isTrue();
        assertThat(SilenceMarkerUtils.isPunctuation('*')).isTrue();
        assertThat(SilenceMarkerUtils.isPunctuation('-')).isTrue();
    }

    @Test
    void isPunctuation_bracketsArePunctuation() {
        // Brackets are punctuation by Character.getType, but stripEdgeSilencePunctuation
        // explicitly preserves them
        assertThat(SilenceMarkerUtils.isPunctuation('[')).isTrue();
        assertThat(SilenceMarkerUtils.isPunctuation(']')).isTrue();
    }

    @Test
    void isPunctuation_lettersAreNotPunctuation() {
        assertThat(SilenceMarkerUtils.isPunctuation('a')).isFalse();
        assertThat(SilenceMarkerUtils.isPunctuation('Z')).isFalse();
        assertThat(SilenceMarkerUtils.isPunctuation('0')).isFalse();
        assertThat(SilenceMarkerUtils.isPunctuation(' ')).isFalse();
    }
}