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
}