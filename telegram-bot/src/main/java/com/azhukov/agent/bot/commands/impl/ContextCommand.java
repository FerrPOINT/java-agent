package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class ContextCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public ContextCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "Show current context size and details";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        JsonNode node = backendClient.getContext(session.getId().toString());
        if (node == null) {
            return "Failed to retrieve context from backend.";
        }
        int messageCount = node.path("messageCount").asInt(0);
        int tokenEstimate = node.path("tokenEstimate").asInt(0);
        String toolsUsed = node.path("toolsUsed").asText("none");
        return "Context info:\n"
            + "  Messages: " + messageCount + "\n"
            + "  Token estimate: " + tokenEstimate + "\n"
            + "  Tools used: " + toolsUsed;
    }
}