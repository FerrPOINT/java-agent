package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void shortText_returnsSingleChunk() {
        var chunks = MessageSplitter.split("Hello, world!");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Hello, world!");
    }

    @Test
    void emptyText_returnsSingleEmptyChunk() {
        var chunks = MessageSplitter.split("");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("");
    }

    @Test
    void nullText_returnsSingleEmptyChunk() {
        var chunks = MessageSplitter.split(null);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("");
    }

    @Test
    void longText_splitsIntoMultipleChunks() {
        // Create text longer than 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("Line ").append(i).append(": This is a test line that adds some length.\n");
        }
        var chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void allChunks_respectMaxLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Line ").append(i).append(": This is a longer test line for splitting.\n");
        }
        var chunks = MessageSplitter.split(sb.toString());
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        }
    }

    @Test
    void splitsOnNewlineBoundary() {
        // Create text where the split should happen at a newline
        StringBuilder sb = new StringBuilder();
        // First chunk worth of text
        for (int i = 0; i < 50; i++) {
            sb.append("A").append(i);
        }
        sb.append("\n");
        // More text
        for (int i = 0; i < 50; i++) {
            sb.append("B").append(i);
        }
        // Make it long enough to require splitting
        while (sb.length() < 5000) {
            sb.append("\nMore content to fill the buffer up to the required length.");
        }
        var chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk should not exceed the limit
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        }
    }

    @Test
    void singleLongLine_hardSplits() {
        // A single line with no newlines that exceeds 4096
        String line = "x".repeat(5000);
        var chunks = MessageSplitter.split(line);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        assertThat(chunks.get(1).length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        // Combined should equal original
        assertThat(String.join("", chunks)).isEqualTo(line);
    }

    @Test
    void paragraphSplits_preferredOverLineSplits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("Paragraph ").append(i).append(" with some content that adds meaningful length.\n\n");
        }
        var chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        }
    }
}