package com.azhukov.agent.bot.formatting;

import org.springframework.stereotype.Component;

/**
 * B2.8: Filters silent/empty responses before sending to Telegram.
 * <p>
 * - Filters content equal to "***" (intentional silence marker)
 * - Filters empty or whitespace-only responses
 */
@Component
public class ResponseFilter {

    /** The silence marker — responses with exactly this content are filtered. */
    private static final String SILENCE_MARKER = "***";

    /**
     * Check if a response should be filtered (not sent to the user).
     *
     * @param content the response content to check
     * @return true if the response should be filtered out, false if it should be sent
     */
    public boolean shouldFilter(String content) {
        if (content == null) {
            return true;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.equals(SILENCE_MARKER)) {
            return true;
        }
        return false;
    }

    /**
     * Filter a response. Returns the content if it should be sent,
     * or null if it should be filtered out.
     *
     * @param content the response content
     * @return the content if not filtered, null if filtered
     */
    public String filter(String content) {
        return shouldFilter(content) ? null : content;
    }
}