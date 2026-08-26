package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link ThinkTagFilter} — extracted from StreamEditor.
 * Tests both the stateful {@link ThinkTagFilter.ThinkScrubber} and the
 * stateless {@link ThinkTagFilter#stripThinkTagsRegex(String)}.
 */
class ThinkTagFilterTest {

    private static final String LT = "\u003C";
    private static final String GT = "\u003E";
    private static final String THINK_OPEN = LT + "think" + GT;
    private static final String THINK_CLOSE = LT + "/think" + GT;
    private static final String THINKING_OPEN = LT + "thinking" + GT;
    private static final String THINKING_CLOSE = LT + "/thinking" + GT;
    private static final String REASONING_OPEN = LT + "reasoning" + GT;
    private static final String REASONING_CLOSE = LT + "/reasoning" + GT;

    // ─── stripThinkTagsRegex tests ───────────────────────────────

    @Test
    void stripThinkTagsRegex_removesClosedThinkBlocks() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(THINK_OPEN + "hidden" + THINK_CLOSE + "rest"))
            .isEqualTo("rest");
    }

    @Test
    void stripThinkTagsRegex_removesClosedThinkingBlocks() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(THINKING_OPEN + "hidden" + THINKING_CLOSE + "rest"))
            .isEqualTo("rest");
    }

    @Test
    void stripThinkTagsRegex_removesClosedReasoningBlocks() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(REASONING_OPEN + "hidden" + REASONING_CLOSE + "rest"))
            .isEqualTo("rest");
    }

    @Test
    void stripThinkTagsRegex_preservesTextBeforeAndAfter() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex("before" + THINK_OPEN + "abc" + THINK_CLOSE + "after"))
            .isEqualTo("beforeafter");
    }

    @Test
    void stripThinkTagsRegex_removesOrphanedOpenTag() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(THINK_OPEN + "only thinking"))
            .isEqualTo("");
    }

    @Test
    void stripThinkTagsRegex_removesStrayClosingTag() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(THINK_CLOSE + "stray"))
            .isEqualTo("stray");
    }

    @Test
    void stripThinkTagsRegex_noTagsPreservedAsIs() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex("no tags here"))
            .isEqualTo("no tags here");
    }

    @Test
    void stripThinkTagsRegex_nullAndEmptyReturnEmpty() {
        assertThat(ThinkTagFilter.stripThinkTagsRegex(null)).isEqualTo("");
        assertThat(ThinkTagFilter.stripThinkTagsRegex("")).isEqualTo("");
    }

    @Test
    void stripThinkTagsRegex_removesReasoningScratchpad() {
        String open = LT + "REASONING_SCRATCHPAD" + GT;
        String close = LT + "/REASONING_SCRATCHPAD" + GT;
        assertThat(ThinkTagFilter.stripThinkTagsRegex(open + "hidden" + close + "visible"))
            .isEqualTo("visible");
    }

    @Test
    void stripThinkTagsRegex_caseInsensitive() {
        // Regex is case-insensitive — should match <THINK> even though exact list has <think>
        String input = LT + "THINK" + GT + "hidden" + LT + "/THINK" + GT + "visible";
        assertThat(ThinkTagFilter.stripThinkTagsRegex(input)).isEqualTo("visible");
    }

    // ─── ThinkScrubber stateful tests ────────────────────────────

    @Test
    void scrubber_removesCompleteThinkBlock() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = THINK_OPEN + "hidden" + THINK_CLOSE + "visible";
        assertThat(scrubber.scrub(input)).isEqualTo("visible");
    }

    @Test
    void scrubber_caseSensitive_lowercaseThinkMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        assertThat(scrubber.scrub(THINK_OPEN + "hidden" + THINK_CLOSE + "visible"))
            .isEqualTo("visible");
    }

    @Test
    void scrubber_caseSensitive_uppercaseThinkNotMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = LT + "THINK" + GT + "hidden" + LT + "/THINK" + GT + "visible";
        String result = scrubber.scrub(input);
        assertThat(result).contains("hidden");
        assertThat(result).contains("visible");
    }

    @Test
    void scrubber_caseSensitive_uppercaseThinkingMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = LT + "THINKING" + GT + "hidden" + LT + "/THINKING" + GT + "visible";
        assertThat(scrubber.scrub(input)).isEqualTo("visible");
    }

    @Test
    void scrubber_boundary_thinkAfterNewlineStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = "preamble\n" + THINK_OPEN + "hidden" + THINK_CLOSE + "\nvisible";
        String result = scrubber.scrub(input);
        assertThat(result).contains("preamble");
        assertThat(result).contains("visible");
        assertThat(result).doesNotContain("hidden");
    }

    @Test
    void scrubber_boundary_inlineThinkInProseNotStripped() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String input = "Let me think about this. " + THINK_OPEN + "hidden" + THINK_CLOSE + " Now I'll answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Let me think about this.");
        assertThat(result).contains("Now I'll answer.");
    }

    @Test
    void scrubber_multiChunk_splitClosingTag() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // First chunk: opening tag + partial closing tag
        String r1 = scrubber.scrub(THINK_OPEN + "hidden content </thin");
        assertThat(r1).isEqualTo("");
        // Second chunk: rest of closing tag + visible text
        String r2 = scrubber.scrub("k>" + "visible");
        assertThat(r2).isEqualTo("visible");
    }

    @Test
    void scrubber_multiChunk_boundaryAfterNewline() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String r1 = scrubber.scrub("preamble\n");
        assertThat(r1).isEqualTo("preamble\n");
        String r2 = scrubber.scrub(THINK_OPEN + "hidden" + THINK_CLOSE + "visible");
        assertThat(r2).isEqualTo("visible");
    }

    @Test
    void scrubber_flushReleasesPendingPartialTag() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        // Feed text ending with a partial opening tag prefix that is NOT confirmed
        // as a think tag. The scrubber stores it as pending; flush() should release it.
        // "<thi" is a prefix of "<think" — stored as pending, but since no full tag
        // was confirmed, flush() releases it as visible text.
        scrubber.scrub("hello <thi");
        String flushed = scrubber.flush();
        // The partial tag should be released as visible text
        assertThat(flushed).isNotEmpty();
    }

    @Test
    void scrubber_flushAfterThinkBlockReturnsEmpty() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        scrubber.scrub(THINK_OPEN + "hidden" + THINK_CLOSE);
        String flushed = scrubber.flush();
        assertThat(flushed).isEqualTo("");
    }

    @Test
    void scrubber_antmlThinkingTagMatched() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        String open = LT + "antml:thinking" + GT;
        String close = LT + "/antml:thinking" + GT;
        String input = open + "hidden reasoning" + close + "visible answer";
        assertThat(scrubber.scrub(input)).isEqualTo("visible answer");
    }

    @Test
    void scrubber_emptyInputReturnsEmpty() {
        ThinkTagFilter.ThinkScrubber scrubber = new ThinkTagFilter.ThinkScrubber();
        assertThat(scrubber.scrub(null)).isEqualTo("");
        assertThat(scrubber.scrub("")).isEqualTo("");
    }
}