package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * A2.9: /topic list|create|switch &lt;name&gt; — Manage DM topic sessions locally.
 * <p>
 * Topic sessions allow separate conversation contexts within a single DM chat.
 * This is a local-only implementation (no backend involvement).
 */
@Component
public class TopicCommand implements CommandHandler {

    private final BotSessionStore sessionStore;
    private final Map<String, String> topicSessions = new HashMap<>();

    public TopicCommand(BotSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

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
        if (topicSessions.isEmpty()) {
            return "No topic sessions. Use /topic create <name> to create one.";
        }
        StringBuilder sb = new StringBuilder("Topic sessions:\n");
        topicSessions.forEach((name, sessionId) ->
            sb.append("  • ").append(name).append(": ").append(sessionId).append("\n"));
        return sb.toString().trim();
    }

    private String createTopic(UpdateEvent event, String topicName) {
        if (topicName.isBlank()) {
            return "Usage: /topic create <name>";
        }
        if (topicSessions.containsKey(topicName)) {
            return "Topic '" + topicName + "' already exists. Use /topic switch " + topicName + ".";
        }
        // Create a new session for this topic
        String userId = String.valueOf(event.userId());
        String chatId = String.valueOf(event.chatId());
        BotSessionEntity newSession = sessionStore.resolveOrCreate(userId, chatId, event.username());
        topicSessions.put(topicName, newSession.getId().toString());
        return "Topic '" + topicName + "' created with session " + newSession.getId() + ".";
    }

    private String switchTopic(UpdateEvent event, String topicName) {
        if (topicName.isBlank()) {
            return "Usage: /topic switch <name>";
        }
        String sessionId = topicSessions.get(topicName);
        if (sessionId == null) {
            return "Topic '" + topicName + "' not found. Use /topic create " + topicName + ".";
        }
        return "Switched to topic '" + topicName + "' (session " + sessionId + ").";
    }
}