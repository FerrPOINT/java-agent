package com.azhukov.agent.bot.client;

/**
 * Typed exception for Telegram Bot API errors.
 * <p>
 * Thrown by {@link TelegramClient#callApi} and {@link TelegramClient#callMultipartApi}
 * on non-200 (i.e. non-{@code ok}) Telegram API responses, allowing callers to
 * inspect the error code (e.g. 400, 429) and description without relying on
 * side-channel state like {@code lastApiErrorCode}.
 *
 * <p>For HTTP 429 (rate limit) responses, {@link #retryAfter} carries the
 * seconds-to-wait value from the Telegram {@code retry_after} parameter
 * (or {@code -1} when absent).
 */
public class TelegramApiException extends RuntimeException {

    private final int errorCode;
    private final String errorDescription;
    private final int retryAfter;

    /**
     * Full constructor.
     *
     * @param errorCode        Telegram API error code (e.g. 400, 403, 429, 409)
     * @param errorDescription human-readable description from Telegram
     * @param retryAfter       seconds to wait before retrying (only meaningful
     *                         for 429 responses); {@code -1} when not provided
     */
    public TelegramApiException(int errorCode, String errorDescription, int retryAfter) {
        super(errorCode + ": " + (errorDescription != null ? errorDescription : "Unknown Telegram API error"));
        this.errorCode = errorCode;
        this.errorDescription = errorDescription;
        this.retryAfter = retryAfter;
    }

    /** Convenience constructor without {@code retryAfter} (defaults to {@code -1}). */
    public TelegramApiException(int errorCode, String errorDescription) {
        this(errorCode, errorDescription, -1);
    }

    /** Telegram API error code (400, 403, 429, 409, ...). */
    public int getErrorCode() {
        return errorCode;
    }

    /** Human-readable error description from Telegram. */
    public String getErrorDescription() {
        return errorDescription;
    }

    /**
     * Seconds to wait before retrying, as indicated by Telegram's
     * {@code retry_after} parameter on 429 responses.
     * Returns {@code -1} when the parameter was absent.
     */
    public int getRetryAfter() {
        return retryAfter;
    }

    /** @return {@code true} if this exception represents a 429 rate-limit error. */
    public boolean isRateLimit() {
        return errorCode == 429;
    }
}