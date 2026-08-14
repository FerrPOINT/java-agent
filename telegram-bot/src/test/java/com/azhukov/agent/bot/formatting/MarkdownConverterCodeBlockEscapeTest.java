package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for P1-7: Escape backslash and backtick inside code blocks and inline code.
 * <p>
 * The MarkdownV2 spec requires escaping \ and ` even inside code blocks.
 * Other special characters should NOT be escaped inside code blocks.
 */
class MarkdownConverterCodeBlockEscapeTest {

    // ─── Code block (```) tests ───────────────────────────────────

    @Test
    void codeBlock_escapesBackslash() {
        String result = MarkdownConverter.convert("```\npath = C:\\Users\\test\n```");
        // \ → \\ inside code blocks
        assertThat(result).isEqualTo("```\npath = C:\\\\Users\\\\test\n```");
    }

    @Test
    void codeBlock_escapesBacktick() {
        String result = MarkdownConverter.convert("```\ncode with `backtick` inside\n```");
        // ` → \` inside code blocks
        assertThat(result).isEqualTo("```\ncode with \\`backtick\\` inside\n```");
    }

    @Test
    void codeBlock_escapesBothBackslashAndBacktick() {
        String result = MarkdownConverter.convert("```\n\\` and `\\`\n```");
        // Input content: \` and `\`  (backslash-backtick space and space backtick-backslash-backtick)
        // After escaping: \\ + \` = \\\`  and  \` + \\ + \` = \`\\\`
        assertThat(result).isEqualTo("```\n\\\\\\` and \\`\\\\\\`\n```");
    }

    @Test
    void codeBlock_doesNotEscapeOtherSpecialChars() {
        // Characters like . ! _ * etc. should NOT be escaped inside code blocks
        String result = MarkdownConverter.convert("```\nif (a.b > c) { return d! }\n```");
        assertThat(result).isEqualTo("```\nif (a.b > c) { return d! }\n```");
    }

    @Test
    void codeBlock_withLanguage_escapesBackslashAndBacktick() {
        String result = MarkdownConverter.convert("```java\nString s = \"\\t\"; `code`\n```");
        // \t → \\t, `code` → \`code\`
        assertThat(result).isEqualTo("```java\nString s = \"\\\\t\"; \\`code\\`\n```");
    }

    @Test
    void codeBlock_noBackslashOrBacktick_unchanged() {
        String result = MarkdownConverter.convert("```\nplain code\n```");
        assertThat(result).isEqualTo("```\nplain code\n```");
    }

    // ─── Inline code (`) tests ────────────────────────────────────

    @Test
    void inlineCode_escapesBackslash() {
        String result = MarkdownConverter.convert("`C:\\path`");
        // \ → \\ inside inline code
        assertThat(result).isEqualTo("`C:\\\\path`");
    }

    @Test
    void inlineCode_escapesBacktick() {
        // Note: the inline code regex `[^`]+` won't match backticks inside,
        // so we test the escape function directly
        assertThat(MarkdownConverter.escapeCodeBlockContent("a`b")).isEqualTo("a\\`b");
    }

    @Test
    void inlineCode_doesNotEscapeOtherSpecialChars() {
        String result = MarkdownConverter.convert("`code with _ and *`");
        assertThat(result).isEqualTo("`code with _ and *`");
    }

    @Test
    void inlineCode_noBackslash_unchanged() {
        String result = MarkdownConverter.convert("`simple code`");
        assertThat(result).isEqualTo("`simple code`");
    }

    // ─── Direct escapeCodeBlockContent tests ──────────────────────

    @Test
    void escapeCodeBlockContent_escapesBackslash() {
        // Input: a\b → Output: a\\b
        assertThat(MarkdownConverter.escapeCodeBlockContent("a\\b")).isEqualTo("a\\\\b");
    }

    @Test
    void escapeCodeBlockContent_escapesBacktick() {
        // Input: a`b → Output: a\`b
        assertThat(MarkdownConverter.escapeCodeBlockContent("a`b")).isEqualTo("a\\`b");
    }

    @Test
    void escapeCodeBlockContent_doesNotEscapeOtherChars() {
        assertThat(MarkdownConverter.escapeCodeBlockContent("a.b_c*d!e"))
            .isEqualTo("a.b_c*d!e");
    }

    @Test
    void escapeCodeBlockContent_emptyReturnsEmpty() {
        assertThat(MarkdownConverter.escapeCodeBlockContent("")).isEqualTo("");
        assertThat(MarkdownConverter.escapeCodeBlockContent(null)).isEqualTo("");
    }

    @Test
    void escapeCodeBlockContent_multipleBackslashes() {
        // Input: \\ (two backslashes)
        // Output: \\\\ (four backslashes — each \ → \\)
        assertThat(MarkdownConverter.escapeCodeBlockContent("\\\\")).isEqualTo("\\\\\\\\");
    }

    @Test
    void escapeCodeBlockContent_mixedBackslashAndBacktick() {
        // Input: \` (backslash + backtick)
        // Output: \\ + \` = \\\` (3 backslashes + backtick)
        assertThat(MarkdownConverter.escapeCodeBlockContent("\\`")).isEqualTo("\\\\\\`");
    }
}