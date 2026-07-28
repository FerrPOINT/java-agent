package com.azhukov.agent.bot.reaction;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages message reactions (👀/👍/👎) on user messages during processing.
 * <p>
 * Config-driven via {@code bot.reactions.enabled}.
 */
@Component
public class ReactionManager {

    private static final Logger log = LoggerFactory.getLogger(ReactionManager.class);

    private static final String EYE = "\uD83D\uDC40";       // 👀
    private static final String THUMBS_UP = "\uD83D\uDC4D";   // 👍
    private static final String THUMBS_DOWN = "\uD83D\uDC4E"; // 👎

    private final TelegramClient telegramClient;
    private final BotProperties properties;

    public ReactionManager(TelegramClient telegramClient, BotProperties properties) {
        this.telegramClient = telegramClient;
        this.properties = properties;
    }

    /**
     * Set 👀 reaction when processing starts.
     */
    public void onProcessingStart(long chatId, long messageId) {
        if (!properties.getReactions().isEnabled() || messageId <= 0) return;
        try {
            telegramClient.setMessageReaction(chatId, messageId, EYE);
        } catch (Exception e) {
            log.debug("Failed to set 👀 reaction on message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    /**
     * Set 👍 or 👎 reaction when processing completes.
     */
    public void onProcessingComplete(long chatId, long messageId, boolean success) {
        if (!properties.getReactions().isEnabled() || messageId <= 0) return;
        try {
            telegramClient.setMessageReaction(chatId, messageId, success ? THUMBS_UP : THUMBS_DOWN);
        } catch (Exception e) {
            log.debug("Failed to set {} reaction on message {} in chat {}: {}",
                success ? "👍" : "👎", messageId, chatId, e.getMessage());
        }
    }

    /**
     * Clear reaction when processing is cancelled.
     */
    public void onCancel(long chatId, long messageId) {
        if (!properties.getReactions().isEnabled() || messageId <= 0) return;
        try {
            telegramClient.setMessageReaction(chatId, messageId, "");
        } catch (Exception e) {
            log.debug("Failed to clear reaction on message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    boolean isEnabled() {
        return properties.getReactions().isEnabled();
    }
}