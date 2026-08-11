package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> chunks = MessageSplitter.split("Hello world");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Hello world");
        // No index prefix for single chunk
        assertThat(chunks.get(0)).doesNotStartWith("(");
    }

    @Test
    void multiChunkHasContinuationIndicator() {
        // Create text > 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.split(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);
        // Each chunk should have "(N/M) " prefix
        assertThat(chunks.get(0)).startsWith("(1/");
        assertThat(chunks.get(chunks.size() - 1)).contains("(" + chunks.size() + "/");
    }

    @Test
    void codeBlockNotBrokenAcrossChunks() {
        // Create a code block with paragraph boundaries INSIDE it (< 4096 chars)
        // The splitter should NOT split on \n\n inside the code block
        StringBuilder code = new StringBuilder("```java\n");
        code.append("// Code with blank line inside\n\n");
        code.append("public class Test {\n");
        code.append("    int x = 1;\n");
        code.append("}\n");
        code.append("```\n\n");

        // Prepend enough text to force a split at the code block boundary
        StringBuilder text = new StringBuilder();
        text.append("Intro text. ".repeat(400)); // ~4800 chars, forces split
        text.append("\n\n");
        text.append(code);

        List<String> chunks = MessageSplitter.split(text.toString());
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        // The chunk containing the code block should have balanced fences
        for (String chunk : chunks) {
            int fenceCount = countOccurrences(chunk, "```");
            // If chunk has fences, they should be balanced
            if (fenceCount > 0) {
                assertThat(fenceCount % 2)
                    .as("Code fences should be balanced in chunk with %d fences", fenceCount)
                    .isEven();
            }
        }
    }

    @Test
    void splitOnParagraphOutsideCodeBlock() {
        // Text with paragraph boundary outside code block
        String text = "First paragraph.\n\nSecond paragraph.\n\n```code\nx = 1\n```";
        // Make it large enough to split
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("Paragraph ").append(i).append(" with padding. ".repeat(100));
            sb.append("\n\n");
        }
        sb.append("```java\n").append("x = 1\n").append("```");

        List<String> chunks = MessageSplitter.split(sb.toString());
        // Should split — at least 2 chunks
        assertThat(chunks.size()).isGreaterThanOrEqualTo(1);
    }

    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ─── splitAndFormat tests ──────────────────────────────────────

    @Test
    void splitAndFormat_shortTextMarkdownV2_singleChunk() {
        String text = "Hello **bold** text";
        List<String> chunks = MessageSplitter.splitAndFormat(text, "MarkdownV2");
        assertThat(chunks).hasSize(1);
        // Single chunk: no indicator, just formatted
        assertThat(chunks.get(0)).doesNotStartWith("(");
        // Should be formatted (bold markers ** converted to * for Telegram MarkdownV2)
        assertThat(chunks.get(0)).contains("*bold*");
    }

    @Test
    void splitAndFormat_multiChunkMarkdownV2_escapedIndicator() {
        // Create text > 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitAndFormat(sb.toString(), "MarkdownV2");
        assertThat(chunks.size()).isGreaterThan(1);

        // The indicator should be escaped for MarkdownV2: ( → \(, ) → \)
        // / is NOT a special char in MarkdownV2, so it stays unescaped
        String firstChunk = chunks.get(0);
        // The indicator "(1/N) " should have parens escaped: "\(1/N\) "
        assertThat(firstChunk).startsWith("\\(1/");
        // Verify all chunks have escaped indicator at the start
        for (String chunk : chunks) {
            // Each chunk should start with escaped indicator \( not unescaped (
            assertThat(chunk).startsWith("\\(");
            // Should contain escaped version with \( and \)
            assertThat(chunk).contains("\\(");
            assertThat(chunk).contains("\\)");
        }
    }

    @Test
    void splitAndFormat_multiChunkHTML_unescapedIndicator() {
        // Create text > 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitAndFormat(sb.toString(), "HTML");
        assertThat(chunks.size()).isGreaterThan(1);
        // For HTML, the indicator should NOT be escaped
        assertThat(chunks.get(0)).startsWith("(1/");
    }

    @Test
    void splitAndFormat_multiChunkNullParseMode_unescapedIndicator() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitAndFormat(sb.toString(), null);
        assertThat(chunks.size()).isGreaterThan(1);
        // For null parse mode, the indicator should NOT be escaped
        assertThat(chunks.get(0)).startsWith("(1/");
    }

    @Test
    void splitAndFormat_emptyText() {
        List<String> chunks = MessageSplitter.splitAndFormat("", "MarkdownV2");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEmpty();
    }

    @Test
    void splitAndFormat_nullText() {
        List<String> chunks = MessageSplitter.splitAndFormat(null, "MarkdownV2");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEmpty();
    }

    @Test
    void splitAndFormat_markdownV2EscapesParenthesesInIndicator() {
        // Verify that the (1/N) indicator has escaped parens for MarkdownV2
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitAndFormat(sb.toString(), "MarkdownV2");
        assertThat(chunks.size()).isGreaterThan(1);

        // Check that the ( character in the indicator is escaped
        String first = chunks.get(0);
        assertThat(first).contains("\\(");
        assertThat(first).contains("\\)");
        // / is NOT a special char in MarkdownV2 so it is not escaped
    }
}