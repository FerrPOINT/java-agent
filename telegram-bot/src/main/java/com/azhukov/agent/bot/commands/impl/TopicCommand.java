package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.settings.BotSettingsService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A2.9: /topic list|create|switch &lt;name&gt; — Manage DM topic sessions locally.
 * <p>
 * Topic sessions allow separate conversation contexts within a single DM chat.
 * This is a local-only implementation (no backend involvement).
 * <p>
 * Topic session mappings are persisted via {@link BotSettingsService} using the key
 * format {@code topic_session:{chatId}:{topicName}} so they survive restarts.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TopicCommand implements CommandHandler {

    private static final String KEY_PREFIX = "topic_session:";

    private final BotSessionStore sessionStore;
    private final BotSettingsService settingsService;

    @Override
    public String name() {
        return "topic";
    }

    @Override
    public String description() {
        return "Manage DM topic sessions";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return "Usage: /topic list|create|switch <name>";
        }

        String[] parts = args.trim().split("\\s+", 2);
        String subcommand = parts[0].toLowerCase();
        String topicName = parts.length > 1 ? parts[1].trim() : "";

        return switch (subcommand) {
            case "list" -> listTopics(event);
            case "create" -> createTopic(event, topicName);
            case "switch" -> switchTopic(event, topicName);
            default -> "Usage: /topic list|create|switch <name>";
        };
    }

    private String listTopics(UpdateEvent event) {
        long chatId = event.chatId();
        String prefix = KEY_PREFIX + chatId + ":";
        Map<String, String> topics = settingsService.getSettingsByPrefix(prefix);
        if (topics.isEmpty()) {
            return "No topic sessions. Use /topic create <name> to create one.";
        }
        StringBuilder sb = new StringBuilder("Topic sessions:\n");
        topics.forEach((key, sessionId) -> {
            String topicName = key.substring(prefix.length());
            sb.append("  • ").append(topicName).append(": ").append(sessionId).append("\n");
        });
        return sb.toString().trim();
    }

    private String createTopic(UpdateEvent event, String topicName) {
        if (topicName.isBlank()) {
            return "Usage: /topic create <name>";
        }
        long chatId = event.chatId();
        String settingKey = settingKey(chatId, topicName);
        String existing = settingsService.getSetting(settingKey, null);
        if (existing != null) {
            return "Topic '" + topicName + "' already exists. Use /topic switch " + topicName + ".";
        }
        // Create a DISTINCT session for this topic (deactivates the current
        // active row so the topic starts with clean backend context).
        String userId = String.valueOf(event.userId());
        String chatIdStr = String.valueOf(chatId);
        BotSessionEntity newSession = sessionStore.createFreshSession(userId, chatIdStr, event.username());
        settingsService.setSetting(settingKey, newSession.getId().toString());
        return "Topic '" + topicName + "' created with session " + newSession.getId()
            + ". You are now chatting in it.";
    }

    private String switchTopic(UpdateEvent event, String topicName) {
        if (topicName.isBlank()) {
            return "Usage: /topic switch <name>";
        }
        String sessionId = settingsService.getSetting(settingKey(event.chatId(), topicName), null);
        if (sessionId == null) {
            return "Topic '" + topicName + "' not found. Use /topic create " + topicName + ".";
        }
        // Actually re-route the ACTIVE session to the topic's session row.
        try {
            boolean switched = sessionStore.activateSessionById(
                String.valueOf(event.userId()), java.util.UUID.fromString(sessionId));
            if (!switched) {
                return "Topic '" + topicName + "' session is gone. Re-create it with /topic create " + topicName + ".";
            }
        } catch (IllegalArgumentException e) {
            return "Topic '" + topicName + "' has an invalid session id.";
        }
        return "Switched to topic '" + topicName + "' (session " + sessionId + ").";
    }

    // ─── helpers ────────────────────────────────────────────────────

    /**
     * Build the settings key for a topic session.
     * Format: {@code topic_session:{chatId}:{topicName}}
     */
    private String settingKey(long chatId, String topicName) {
        return KEY_PREFIX + chatId + ":" + topicName;
    }
}