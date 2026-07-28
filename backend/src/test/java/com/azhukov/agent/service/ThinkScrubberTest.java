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
        assertThat(r3).isEmpty();

        String r4 = scrubber.scrub("visible");
        assertThat(r4).contains("visible");
        assertThat(r4).doesNotContain("secret");
    }

    @Test
    void flush_returnsRemainingContent() {
        ThinkScrubber scrubber = new ThinkScrubber();

        // "Hello World" ends with "<" which could be the start of "<think>"
        // so "Hello World" is returned immediately and "<" is held in the buffer
        String r = scrubber.scrub("Hello World<");
        assertThat(r).contains("Hello World");

        // flush() should return the held "<" since it's not a complete tag
        String flushed = scrubber.flush();
        assertThat(flushed).isNotEmpty();
        assertThat(flushed).contains("<");
    }
}
