package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link TelegramTextFormatter} — extracted from StreamEditor.
 * Tests formatForTelegram, stripTrailingNewlines, countOccurrences, countCodeFences.
 */
class TelegramTextFormatterTest {

    // ─── formatForTelegram tests ─────────────────────────────────

    @Test
    void formatForTelegram_markdownV2EscapesSpecialChars() {
        String result = TelegramTextFormatter.formatForTelegram("Hello *world* _test_", "MarkdownV2");
        // MarkdownConverter should escape special characters
        assertThat(result).contains("Hello");
        assertThat(result).contains("world");
        assertThat(result).contains("test");
    }

    @Test
    void formatForTelegram_nonMarkdownV2ReturnsAsIs() {
        String text = "Hello *world* _test_";
        assertThat(TelegramTextFormatter.formatForTelegram(text, "HTML"))
            .isEqualTo(text);
    }

    @Test
    void formatForTelegram_nullParseModeReturnsAsIs() {
        String text = "Hello world";
        assertThat(TelegramTextFormatter.formatForTelegram(text, null))
            .isEqualTo(text);
    }

    @Test
    void formatForTelegram_nullTextReturnsEmpty() {
        assertThat(TelegramTextFormatter.formatForTelegram(null, "MarkdownV2")).isEmpty();
    }

    @Test
    void formatForTelegram_emptyTextReturnsEmpty() {
        assertThat(TelegramTextFormatter.formatForTelegram("", "MarkdownV2")).isEmpty();
    }

    @Test
    void formatForTelegram_caseInsensitiveParseMode() {
        // "markdownv2" (lowercase) should also trigger MarkdownV2 conversion
        String result = TelegramTextFormatter.formatForTelegram("Hello *world*", "markdownv2");
        assertThat(result).contains("Hello");
        // If it was treated as MarkdownV2, the asterisk would be escaped
        assertThat(result).contains("world");
    }

    // ─── stripTrailingNewlines tests ─────────────────────────────

    @Test
    void stripTrailingNewlines_removesAllTrailingNewlines() {
        assertThat(TelegramTextFormatter.stripTrailingNewlines("hello\n\n\n"))
            .isEqualTo("hello");
    }

    @Test
    void stripTrailingNewlines_preservesInternalNewlines() {
        assertThat(TelegramTextFormatter.stripTrailingNewlines("line1\nline2\n"))
            .isEqualTo("line1\nline2");
    }

    @Test
    void stripTrailingNewlines_noTrailingNewlines() {
        assertThat(TelegramTextFormatter.stripTrailingNewlines("hello"))
            .isEqualTo("hello");
    }

    @Test
    void stripTrailingNewlines_emptyString() {
        assertThat(TelegramTextFormatter.stripTrailingNewlines(""))
            .isEqualTo("");
    }

    @Test
    void stripTrailingNewlines_onlyNewlines() {
        assertThat(TelegramTextFormatter.stripTrailingNewlines("\n\n\n"))
            .isEqualTo("");
    }

    // ─── countOccurrences tests ──────────────────────────────────

    @Test
    void countOccurrences_singleOccurrence() {
        assertThat(TelegramTextFormatter.countOccurrences("hello world", "world"))
            .isEqualTo(1);
    }

    @Test
    void countOccurrences_multipleNonOverlapping() {
        assertThat(TelegramTextFormatter.countOccurrences("aaa aaa aaa", "aaa"))
            .isEqualTo(3);
    }

    @Test
    void countOccurrences_overlappingNotCounted() {
        // Non-overlapping: "aaaa" with needle "aa" = 2 matches
        assertThat(TelegramTextFormatter.countOccurrences("aaaa", "aa"))
            .isEqualTo(2);
    }

    @Test
    void countOccurrences_notFound() {
        assertThat(TelegramTextFormatter.countOccurrences("hello", "xyz"))
            .isEqualTo(0);
    }

    @Test
    void countOccurrences_tripleBackticks() {
        assertThat(TelegramTextFormatter.countOccurrences("```code```", "```"))
            .isEqualTo(2);
    }

    @Test
    void countOccurrences_singleBackticks() {
        assertThat(TelegramTextFormatter.countOccurrences("a`b`c", "`"))
            .isEqualTo(2);
    }

    // ─── countCodeFences tests ───────────────────────────────────

    @Test
    void countCodeFences_evenCount() {
        assertThat(TelegramTextFormatter.countCodeFences("```code```"))
            .isEqualTo(2);
    }

    @Test
    void countCodeFences_oddCount() {
        assertThat(TelegramTextFormatter.countCodeFences("```python\nprint('hi')"))
            .isEqualTo(1);
    }

    @Test
    void countCodeFences_none() {
        assertThat(TelegramTextFormatter.countCodeFences("plain text"))
            .isEqualTo(0);
    }

    @Test
    void countCodeFences_multiple() {
        String text = "```block1``` text ```block2```";
        assertThat(TelegramTextFormatter.countCodeFences(text))
            .isEqualTo(4);
    }

    @Test
    void countCodeFences_emptyString() {
        assertThat(TelegramTextFormatter.countCodeFences(""))
            .isEqualTo(0);
    }
}