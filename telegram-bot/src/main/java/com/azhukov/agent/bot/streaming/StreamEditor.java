package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages edit-message streaming: sends an initial message, then edits it
 * as more content arrives. Throttles edits to {@code streamEditInterval}
 * to avoid hitting Telegram's rate limits.
 */
@Service
public class StreamEditor {

    private static final Logger log = LoggerFactory.getLogger(StreamEditor.class);

    private final TelegramClient telegramClient;
    private final String parseMode;
    private final long editIntervalMs;
    private final Map<Long, Long> lastEditTime = new ConcurrentHashMap<>();

    public StreamEditor(TelegramClient telegramClient, BotProperties properties) {
        this.telegramClient = telegramClient;
        this.parseMode = properties.getParseMode();
        this.editIntervalMs = properties.getStreamEditInterval().toMillis();
    }

    /**
     * Sends the initial streaming message.
     *
     * @param chatId      target chat id
     * @param initialText first chunk of text to display
     * @return the message id wrapped in Optional, or empty if the send failed
     */
    public Optional<Long> startStream(long chatId, String initialText) {
        Optional<Long> messageId = telegramClient.sendMessage(chatId, initialText, parseMode, null, null);
        if (messageId.isPresent()) {
            lastEditTime.put(chatId, System.currentTimeMillis());
            log.debug("Started stream for chat {}, messageId={}", chatId, messageId.get());
        } else {
            log.warn("Failed to start stream for chat {}", chatId);
        }
        return messageId;
    }

    /**
     * Edits the streaming message with updated text. Throttled to
     * {@code streamEditInterval} — calls made too soon after the last edit
     * are silently skipped.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param text      the full accumulated text to display
     * @return {@code true} if the edit was sent, {@code false} if throttled or failed
     */
    public boolean editStream(long chatId, long messageId, String text) {
        long now = System.currentTimeMillis();
        Long last = lastEditTime.get(chatId);
        if (last != null && (now - last) < editIntervalMs) {
            log.trace("Throttled edit for chat {} ({}ms since last)", chatId, now - last);
            return false;
        }
        boolean success = telegramClient.editMessageText(chatId, messageId, text, parseMode);
        if (success) {
            lastEditTime.put(chatId, now);
        }
        return success;
    }

    /**
     * Final edit to the streaming message. Always sends regardless of throttle
     * interval, since this is the last update.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param finalText the complete final text
     * @return {@code true} if the edit succeeded
     */
    public boolean finalizeStream(long chatId, long messageId, String finalText) {
        boolean success = telegramClient.editMessageText(chatId, messageId, finalText, parseMode);
        lastEditTime.remove(chatId);
        if (success) {
            log.debug("Finalized stream for chat {}, messageId={}", chatId, messageId);
        } else {
            log.warn("Failed to finalize stream for chat {}, messageId={}", chatId, messageId);
        }
        return success;
    }

    /**
     * Clears throttle state for a chat (e.g. after an error or session reset).
     *
     * @param chatId target chat id
     */
    public void clearStream(long chatId) {
        lastEditTime.remove(chatId);
    }
}