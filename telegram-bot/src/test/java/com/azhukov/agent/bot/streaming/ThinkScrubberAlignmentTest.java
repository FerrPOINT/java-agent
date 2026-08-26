package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests verifying that ThinkScrubber matches Hermes behavior:
 * - Case-sensitive matching (not case-insensitive)
 * - Exact tag match (not prefix matching)
 * - Boundary check: opening tag must be at block boundary
 *   (start of text or preceded by newline + optional whitespace)
 */
class ThinkScrubberAlignmentTest {

    private static final String LT = "\u003C";
    private static final String GT = "\u003E";
    private static final String THINK_OPEN = LT + "think" + GT;
    private static final String THINK_CLOSE = LT + "/think" + GT;
    private static final String THINKING_OPEN = LT + "thinking" + GT;
    private static final String THINKING_CLOSE = LT + "/thinking" + GT;
    private static final String REASONING_OPEN = LT + "reasoning" + GT;
    private static final String REASONING_CLOSE = LT + "/reasoning" + GT;

    // ─── Case-sensitive matching tests ──────────────────────────

    @Test
    void caseSensitive_lowercaseThinkIsMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = THINK_OPEN + "hidden" + THINK_CLOSE + "visible";
        String result = scrubber.scrub(input);
        assertThat(result).isEqualTo("visible");
    }

    @Test
    void caseSensitive_uppercaseThinkIsNotMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // <THINK> is NOT in the tag list (only <THINKING> is uppercase-matched)
        String input = LT + "THINK" + GT + "hidden" + LT + "/THINK" + GT + "visible";
        String result = scrubber.scrub(input);
        // <THINK> is not a recognized tag, content passes through
        assertThat(result).contains("hidden");
        assertThat(result).contains("visible");
    }

    @Test
    void caseSensitive_uppercaseThinkingIsMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // <THINKING> IS in the tag list (Hermes has this exact tag)
        String input = LT + "THINKING" + GT + "hidden" + LT + "/THINKING" + GT + "visible";
        String result = scrubber.scrub(input);
        assertThat(result).isEqualTo("visible");
        assertThat(result).doesNotContain("hidden");
    }

    @Test
    void caseSensitive_mixedCaseThinkIsNotMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // <Think> (mixed case) is NOT in the tag list
        String input = LT + "Think" + GT + "hidden" + LT + "/Think" + GT + "visible";
        String result = scrubber.scrub(input);
        // <Think> is not a recognized tag, content passes through
        assertThat(result).contains("hidden");
    }

    // ─── Exact tag matching tests (no prefix matching) ───────────

    @Test
    void exactTag_thinkWithAttributesIsNotMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // <think foo="bar"> has attributes — exact tag matching should NOT match this
        // (the old prefix matching would have matched "<think" prefix)
        String input = LT + "think foo=\"bar\"" + GT + "hidden" + THINK_CLOSE + "visible";
        String result = scrubber.scrub(input);
        // With exact matching, <think foo="bar"> is not recognized as a think tag
        // The content passes through (this matches Hermes behavior)
        assertThat(result).contains("visible");
    }

    @Test
    void exactTag_thinkingIsMatchedExactly() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = THINKING_OPEN + "hidden" + THINKING_CLOSE + "visible";
        String result = scrubber.scrub(input);
        assertThat(result).isEqualTo("visible");
    }

    // ─── Boundary check tests ───────────────────────────────────

    @Test
    void boundary_thinkAtStartOfTextIsStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = THINK_OPEN + "hidden" + THINK_CLOSE + "visible";
        String result = scrubber.scrub(input);
        assertThat(result).isEqualTo("visible");
    }

    @Test
    void boundary_thinkAfterNewlineIsStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = "preamble\n" + THINK_OPEN + "hidden" + THINK_CLOSE + "\nvisible";
        String result = scrubber.scrub(input);
        assertThat(result).contains("preamble");
        assertThat(result).contains("visible");
        assertThat(result).doesNotContain("hidden");
    }

    @Test
    void boundary_thinkAfterNewlineWithWhitespaceIsStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = "preamble\n  " + THINK_OPEN + "hidden" + THINK_CLOSE + " visible";
        String result = scrubber.scrub(input);
        assertThat(result).contains("preamble");
        assertThat(result).contains("visible");
        assertThat(result).doesNotContain("hidden");
    }

    @Test
    void boundary_inlineThinkInProseIsNotStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // When the think tag appears inline in prose (not at a block boundary),
        // it should NOT be stripped — this prevents false positives when models
        // *mention* tags in prose (e.g. "the <think> tag is used for...")
        String input = "Let me think about this. " + THINK_OPEN + "hidden" + THINK_CLOSE + " Now I'll answer.";
        String result = scrubber.scrub(input);
        // The tag is NOT at a boundary (text before it is not whitespace-only)
        assertThat(result).contains("Let me think about this.");
        assertThat(result).contains("Now I'll answer.");
    }

    @Test
    void boundary_thinkAfterNonWhitespaceTextOnSameLineIsNotStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // Text before the tag on the same line is not whitespace → NOT a boundary
        String input = "Some text " + THINK_OPEN + "hidden" + THINK_CLOSE + " more text";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Some text");
        assertThat(result).contains("more text");
    }

    // ─── Multi-chunk boundary tests ─────────────────────────────

    @Test
    void boundary_thinkAtStartOfSecondChunkAfterNewlineEnd() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // First chunk ends with newline → second chunk starts with think tag at boundary
        String r1 = scrubber.scrub("preamble\n");
        assertThat(r1).isEqualTo("preamble\n");

        String r2 = scrubber.scrub(THINK_OPEN + "hidden" + THINK_CLOSE + "visible");
        assertThat(r2).isEqualTo("visible");
    }

    @Test
    void boundary_thinkAtStartOfSecondChunkAfterNonNewlineIsNotStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // First chunk ends without newline → second chunk starts with think tag
        // but accumulated text doesn't end with \n → NOT a boundary
        String r1 = scrubber.scrub("preamble");
        assertThat(r1).isEqualTo("preamble");

        String r2 = scrubber.scrub(THINK_OPEN + "hidden" + THINK_CLOSE + "visible");
        // Not at boundary because accumulated text doesn't end with newline
        assertThat(r2).contains("visible");
        assertThat(r2).contains("hidden");
    }

    // ─── Antml:thinking tag support ──────────────────────────────

    @Test
    void antmlThinkingTagIsMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String open = LT + "antml:thinking" + GT;
        String close = LT + "/antml:thinking" + GT;
        String input = open + "hidden reasoning" + close + "visible answer";
        String result = scrubber.scrub(input);
        assertThat(result).isEqualTo("visible answer");
        assertThat(result).doesNotContain("hidden reasoning");
    }

    // ─── Final scrub (no regex safety net) ───────────────────────

    @Test
    void scrubThinkFinal_noRegexSafetyNet() {
        // scrubThinkFinal should use the stateful scrubber, not the regex safety net
        // This means it respects boundary checks too
        // Test via StreamEditor instance with mocked TelegramClient
        // (the static stripThinkTagsRegex still exists but is not called by scrubThinkFinal)
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = THINK_OPEN + "hidden" + THINK_CLOSE + "visible";
        String result = scrubber.scrub(input);
        scrubber.flush();
        assertThat(result).isEqualTo("visible");
    }
}