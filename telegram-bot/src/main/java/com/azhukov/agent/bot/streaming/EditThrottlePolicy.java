package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import lombok.extern.slf4j.Slf4j;

/**
 * B5: Adaptive rate-limiting policy extracted from {@link StreamEditor}.
 *
 * <p>Pure policy decisions on {@link StreamSession} interval state:
 * <ul>
 *   <li>{@link #getEffectiveInterval} — returns the current edit interval,
 *       falling back to the configured minimum when no backoff is active.</li>
 *   <li>{@link #increaseInterval} — doubles the edit interval on 429 floods,
 *       capped at {@value #MAX_INTERVAL_MS} ms.</li>
 *   <li>{@link #handleEditFailure} — distinguishes 400 (truncation retry) from
 *       429 (flood strike), increments strikes, increases the interval, and
 *       disables streaming edits after {@value #MAX_FLOOD_STRIKES} consecutive
 *       floods.</li>
 * </ul>
 *
 * <p>All methods are static and operate on the session's interval / strike
 * fields plus the injected {@link TelegramClient} for retry attempts.
 */
@Slf4j
final class EditThrottlePolicy {

    private EditThrottlePolicy() {
    }

    // ─── B5: Adaptive rate limiting constants ──────────────────────
    /** Maximum edit interval (ms) — backoff never exceeds this. */
    static final long MAX_INTERVAL_MS = 10000;
    /** Multiplier applied to the interval on each flood (×2). */
    static final double FLOOD_MULTIPLIER = 2.0;
    /** Consecutive floods after which streaming edits are disabled. */
    static final int MAX_FLOOD_STRIKES = 3;
    /** Flood strike count at which a warning is logged. */
    static final int FLOOD_WARN_THRESHOLD = 3;

    // ─── Interval policy ───────────────────────────────────────────

    /**
     * Return the effective edit interval for the session.
     * If no backoff has been applied (interval == 0), returns the configured
     * minimum interval.
     *
     * @param session     the per-chat session
     * @param minIntervalMs the configured minimum interval (ms)
     * @return the effective interval in ms
     */
    static long getEffectiveInterval(StreamSession session, long minIntervalMs) {
        long interval = session.editInterval.get();
        if (interval == 0L) {
            return minIntervalMs;
        }
        return interval;
    }

    /**
     * Increase the edit interval exponentially (×2, capped at
     * {@value #MAX_INTERVAL_MS} ms). Logs the change.
     *
     * @param session       the per-chat session
     * @param minIntervalMs the configured minimum interval (ms)
     */
    static void increaseInterval(StreamSession session, long minIntervalMs) {
        long current = session.editInterval.get();
        if (current == 0L) {
            current = minIntervalMs;
        }
        long newInterval = Math.min((long) (current * FLOOD_MULTIPLIER), MAX_INTERVAL_MS);
        if (newInterval != current) {
            log.info("Increasing edit interval from {}ms to {}ms due to flood", current, newInterval);
            session.editInterval.set(newInterval);
        }
    }

    // ─── Failure handling ─────────────────────────────────────────

    /**
     * Handle edit failure. Distinguishes 400 (message too long) from 429 (flood).
     * <ul>
     *   <li>400: don't increment flood strikes, just truncate and retry.
     *       Returns {@code true} if the truncated retry succeeds, {@code false} otherwise.</li>
     *   <li>429 or other: increment flood strikes, increase interval, possibly
     *       disable streaming edits. Returns {@code false}.</li>
     * </ul>
     *
     * @param telegramClient     the Telegram client for retry attempts
     * @param chatId             target chat id
     * @param messageId          the message id being edited
     * @param formatted          the formatted text that failed
     * @param session            the per-chat session
     * @param errorCode          the Telegram API error code (400, 429, etc.)
     * @param minIntervalMs      the configured minimum interval (ms)
     * @param streamingSilent    whether streaming edits are sent silently
     * @param streamingMaxChars  the configured max chars for truncation fallback
     * @param formatForTelegram  function to format text for Telegram (MarkdownV2 escaping)
     * @return {@code true} if the failure was handled successfully (e.g. truncated
     *         retry succeeded), {@code false} otherwise
     */
    static boolean handleEditFailure(
            TelegramClient telegramClient,
            long chatId,
            long messageId,
            String formatted,
            StreamSession session,
            int errorCode,
            long minIntervalMs,
            boolean streamingSilent,
            int streamingMaxChars,
            java.util.function.Function<String, String> formatForTelegram) {

        if (errorCode == 400) {
            // Message too long — don't increment flood strikes, just truncate and retry.
            // BUG FIX (audit H14): the truncation target must respect the Telegram
            // editMessageText limit (4096 UTF-16 units), NOT streamingMaxChars —
            // the configured split threshold (32768) is a rich-message limit and
            // exceeds what editMessageText accepts, so truncating to it was a no-op
            // and the edit froze.
            log.debug("Edit failed with 400 (message too long) for chat {}, truncating and retrying", chatId);
            int safeLen = Math.min(streamingMaxChars > 0 ? streamingMaxChars : 4000, 4000);
            String truncated = formatted.length() > safeLen ? formatted.substring(0, safeLen) : formatted;
            boolean disableNotification = streamingSilent;
            boolean retried;
            try {
                // L12: Use null parseMode in retry — the original editMessageText call for
                // streaming content uses null (raw text), so the truncated retry should match.
                retried = telegramClient.editMessageText(chatId, messageId, truncated, null, disableNotification);
            } catch (TelegramApiException retryEx) {
                if (retryEx.isRateLimit()) {
                    // Truncated retry also got 429 — treat as flood
                    log.debug("Truncated retry got 429 for chat {}", chatId);
                    return false;
                }
                throw retryEx;
            }
            if (retried) {
                session.lastEditTime = System.currentTimeMillis();
                session.floodStrikes.set(0);
            }
            return retried;
        }

        // 429 or other failure — increment flood strikes
        int strikes = session.floodStrikes.incrementAndGet();
        log.debug("Edit failure (errorCode={}) for chat {}, flood strikes: {}", errorCode, chatId, strikes);

        // Increase interval on flood
        increaseInterval(session, minIntervalMs);

        if (strikes >= FLOOD_WARN_THRESHOLD) {
            log.warn("Flood strike threshold ({}) reached for chat {}, current interval: {}ms",
                strikes, chatId, getEffectiveInterval(session, minIntervalMs));
        }

        if (strikes >= MAX_FLOOD_STRIKES) {
            log.warn("Max flood strikes ({}) exceeded for chat {}, disabling streaming edits — buffering until final",
                MAX_FLOOD_STRIKES, chatId);
            session.streamingDisabled = true;
            // P2-16: Initialize the flood fallback buffer with the current content.
            // The buffer is sent via sendFormattedMessage (parseMode=MarkdownV2) on finalize,
            // so apply formatForTelegram here to escape special chars correctly.
            session.floodFallbackBuffer.append(formatForTelegram.apply(formatted));
        }
        return false;
    }
}