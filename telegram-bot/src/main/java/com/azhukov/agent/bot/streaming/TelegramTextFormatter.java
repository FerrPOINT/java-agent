package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.formatting.MarkdownConverter;

/**
 * Telegram text formatting utilities — extracted from {@link StreamEditor}.
 *
 * <p>Provides static helpers for formatting text for Telegram delivery,
 * including MarkdownV2 conversion, trailing newline stripping, and
 * code fence counting.
 */
public final class TelegramTextFormatter {

    private TelegramTextFormatter() { }

    /**
     * Formats text for Telegram based on the configured parse mode.
     * For MarkdownV2, escapes special characters using {@link MarkdownConverter}.
     *
     * @param text     raw text from the LLM
     * @param parseMode the configured parse mode (e.g. "MarkdownV2")
     * @return formatted text safe for the configured parse mode
     */
    public static String formatForTelegram(String text, String parseMode) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            return MarkdownConverter.convert(text);
        }
        return text;
    }

    /**
     * Strip trailing newline characters from the end of a string.
     *
     * @param s the input string
     * @return the string with trailing {@code \n} characters removed
     */
    public static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Count the number of occurrences of a needle substring in a haystack.
     *
     * @param haystack the string to search in
     * @param needle   the substring to count
     * @return the number of non-overlapping occurrences
     */
    public static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Count the number of {@code ```} (triple backtick) code fence markers in text.
     *
     * @param text the input text
     * @return the number of triple-backtick occurrences
     */
    public static int countCodeFences(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("```", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }
}