package com.azhukov.agent.core.util;

/**
 * M21: Unified text truncation utility — replaces 8 duplicate truncate() implementations.
 */
public final class TextTruncator {

    private TextTruncator() {}

    /**
     * Truncate string to max chars, appending "..." if truncated.
     *
     * @param s the string to truncate (null-safe)
     * @param max maximum length of the result including ellipsis
     * @return truncated string with "..." suffix, or original if shorter
     */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        if (max <= 3) return s.substring(0, max);
        return s.substring(0, max - 3) + "...";
    }
}