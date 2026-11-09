package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> chunks = MessageSplitter.splitPlainText("Hello world");
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
        List<String> chunks = MessageSplitter.splitPlainText(sb.toString());
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

        List<String> chunks = MessageSplitter.splitMarkdown(text.toString()).stream().map(MessageSplitter.TextPart::formatted).toList();
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
    void longCodeFenceIsBalancedAcrossIndependentlyDeliveredChunks() {
        String text = "intro\n```python\n" + "x = 1\n".repeat(1200) + "```\nfinal";

        List<String> chunks = MessageSplitter.splitMarkdown(text).stream().map(MessageSplitter.TextPart::formatted).toList();

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(countOccurrences(chunk, "```") % 2)
                .as("every chunk must render its code block independently")
                .isZero();
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
        }
        assertThat(chunks.get(1)).contains("```python\n");
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

    // ─── Delivery chunk tests ──────────────────────────────────────

    @Test
    void deliveryChunks_shortTextMarkdownV2_singleChunk() {
        String text = "Hello **bold** text";
        List<String> chunks = MessageSplitter.splitMarkdown(text).stream().map(MessageSplitter.TextPart::formatted).toList();
        assertThat(chunks).hasSize(1);
        // Single chunk: no indicator, just formatted
        assertThat(chunks.get(0)).doesNotStartWith("(");
        // Should be formatted (bold markers ** converted to * for Telegram MarkdownV2)
        assertThat(chunks.get(0)).contains("*bold*");
    }

    @Test
    void deliveryChunks_multiChunkMarkdownV2_escapedIndicator() {
        // Create text > 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitMarkdown(sb.toString()).stream().map(MessageSplitter.TextPart::formatted).toList();
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
    void deliveryChunks_multiChunkHTML_unescapedIndicator() {
        // Create text > 4096 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitHtmlSource(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);
        // For HTML, the indicator should NOT be escaped
        assertThat(chunks.get(0)).startsWith("(1/");
    }

    @Test
    void deliveryChunks_multiChunkNullParseMode_unescapedIndicator() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitPlainText(sb.toString());
        assertThat(chunks.size()).isGreaterThan(1);
        // For null parse mode, the indicator should NOT be escaped
        assertThat(chunks.get(0)).startsWith("(1/");
    }

    @Test
    void deliveryChunks_emptyText() {
        List<String> chunks = MessageSplitter.splitMarkdown("").stream().map(MessageSplitter.TextPart::formatted).toList();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEmpty();
    }

    @Test
    void deliveryChunks_nullText() {
        List<String> chunks = MessageSplitter.splitMarkdown(null).stream().map(MessageSplitter.TextPart::formatted).toList();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEmpty();
    }

    @Test
    void deliveryChunks_markdownV2EscapesParenthesesInIndicator() {
        // Verify that the (1/N) indicator has escaped parens for MarkdownV2
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Paragraph ").append(i).append(" with some text. ".repeat(100));
            sb.append("\n\n");
        }
        List<String> chunks = MessageSplitter.splitMarkdown(sb.toString()).stream().map(MessageSplitter.TextPart::formatted).toList();
        assertThat(chunks.size()).isGreaterThan(1);

        // Check that the ( character in the indicator is escaped
        String first = chunks.get(0);
        assertThat(first).contains("\\(");
        assertThat(first).contains("\\)");
        // / is NOT a special char in MarkdownV2 so it is not escaped
    }
}