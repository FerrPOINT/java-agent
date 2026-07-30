package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkScrubberTest {

    @Test
    void scrub_completeThinkBlock_stripsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <think>internal reasoning</think> World");

        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("internal reasoning");
    }

    @Test
    void scrub_splitThinkBlock_handlesBoundary() {
        ThinkScrubber scrubber = new ThinkScrubber();

        String r1 = scrubber.scrub("<think>");
        assertThat(r1).isEmpty();

        String r2 = scrubber.scrub("secret");
        assertThat(r2).isEmpty();

        String r3 = scrubber.scrub("</think>");

        String r4 = scrubber.scrub("visible");
        assertThat(r4).contains("visible");
        assertThat(r4).doesNotContain("secret");
    }

    @Test
    void flush_returnsRemainingContent() {
        ThinkScrubber scrubber = new ThinkScrubber();

        String r = scrubber.scrub("Hello World<");
        assertThat(r).contains("Hello World");

        String flushed = scrubber.flush();
        assertThat(flushed).isNotEmpty();
        assertThat(flushed).contains("<");
    }

    // ── New tag variant tests ──────────────────────────────────────

    @Test
    void scrub_thinkingTag_stripsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <thinking>internal reasoning</thinking> World");
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("internal reasoning");
    }

    @Test
    void scrub_reasoningTag_stripsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <reasoning>step by step</reasoning> World");
        assertThat(result).contains("Hello");
        assertThat(result).contains("World");
        assertThat(result).doesNotContain("step by step");
    }

    @Test
    void scrub_thoughtTag_stripsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <thought>my thoughts</thought> World");
        assertThat(result).contains("Hello");
        assertThat(result).doesNotContain("my thoughts");
    }

    @Test
    void scrub_reasoningScratchpadTag_stripsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <REASONING_SCRATCHPAD>scratch</REASONING_SCRATCHPAD> World");
        assertThat(result).contains("Hello");
        assertThat(result).doesNotContain("scratch");
    }

    @Test
    void scrub_caseInsensitive_thinkingTag() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello <THINKING>internal</THINKING> World");
        assertThat(result).contains("Hello");
        assertThat(result).doesNotContain("internal");
    }

    @Test
    void scrub_caseInsensitive_thinkTag() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Hello  <tHiNk>internal</tHiNk> World");
        assertThat(result).contains("Hello");
        assertThat(result).doesNotContain("internal");
    }

    // ── Block-boundary rule tests ──────────────────────────────────

    @Test
    void blockBoundary_openTagAtStartOfStream_treatedAsOpener() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String r1 = scrubber.scrub("<thinking>");
        assertThat(r1).isEmpty();
        String r2 = scrubber.scrub("secret content");
        assertThat(r2).isEmpty();
        String r3 = scrubber.scrub("</thinking>");
        assertThat(r3).isEmpty();
        String r4 = scrubber.scrub("visible");
        assertThat(r4).contains("visible");
    }

    @Test
    void blockBoundary_openTagAfterNewline_treatedAsOpener() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String result = scrubber.scrub("Line one\n<thinking>hidden</thinking>\nLine two");
        assertThat(result).contains("Line one");
        assertThat(result).contains("Line two");
        assertThat(result).doesNotContain("hidden");
    }

    @Test
    void blockBoundary_openTagMidLine_notTreatedAsOpener() {
        ThinkScrubber scrubber = new ThinkScrubber();
        // "use  <think> tags here" — open tag mid-line should NOT be treated as opener
        String result = scrubber.scrub("Text about using  <think> tags here is fine");
        // The <think> mid-line should not suppress content (it's not at a block boundary)
        assertThat(result).contains("Text about using");
    }

    @Test
    void reset_clearsState() {
        ThinkScrubber scrubber = new ThinkScrubber();
        scrubber.scrub("");

        scrubber.reset();
        String result = scrubber.scrub("visible content");
        assertThat(result).contains("visible content");
    }

    @Test
    void scrub_closedPairMidLine_alwaysSuppressed() {
        ThinkScrubber scrubber = new ThinkScrubber();
        // Closed pairs are always suppressed regardless of boundary
        String result = scrubber.scrub("Some text  <thinking>leak</thinking> more text");
        assertThat(result).contains("Some text");
        assertThat(result).contains("more text");
        assertThat(result).doesNotContain("leak");
    }

    @Test
    void flush_insideBlock_discardsContent() {
        ThinkScrubber scrubber = new ThinkScrubber();
        scrubber.scrub("text before <thinking>");
        scrubber.scrub("hidden reasoning that is not closed");
        String flushed = scrubber.flush();
        assertThat(flushed).isEmpty();
    }

    @Test
    void flush_outsideBlock_returnsRemaining() {
        ThinkScrubber scrubber = new ThinkScrubber();
        scrubber.scrub("visible text with partial <thin");
        String flushed = scrubber.flush();
        assertThat(flushed).isNotEmpty();
    }

    @Test
    void scrub_emptyChunk_returnsEmpty() {
        ThinkScrubber scrubber = new ThinkScrubber();
        assertThat(scrubber.scrub("")).isEmpty();
        assertThat(scrubber.scrub(null)).isEmpty();
    }

    @Test
    void scrub_multipleThinkBlocksInSequence() {
        ThinkScrubber scrubber = new ThinkScrubber();
        String input = "Start  <think>first</think> middle <think>second</think> End";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Start");
        assertThat(result).contains("middle");
        assertThat(result).contains("End");
        assertThat(result).doesNotContain("first");
        assertThat(result).doesNotContain("second");
    }
}