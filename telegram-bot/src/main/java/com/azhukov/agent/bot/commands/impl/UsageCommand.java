package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class UsageCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public UsageCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "Show token usage statistics";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        JsonNode node = backendClient.getUsage(session.getId().toString());
        if (node == null) {
            return "Failed to retrieve usage from backend.";
        }
        int messageCount = node.path("messageCount").asInt(0);
        int tokenEstimate = node.path("tokenEstimate").asInt(0);
        return "Usage:\n"
            + "  Messages: " + messageCount + "\n"
            + "  Token estimate: " + tokenEstimate;
    }
}