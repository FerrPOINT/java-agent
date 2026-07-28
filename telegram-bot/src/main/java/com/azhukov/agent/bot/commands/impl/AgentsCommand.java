package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() {
        return "agents";
    }

    @Override
    public String description() {
        return "List active agents";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode agents = backendClient.listActiveAgents();
        if (agents == null || !agents.isArray() || agents.isEmpty()) {
            return "No active agents.";
        }
        StringBuilder sb = new StringBuilder("Active agents (").append(agents.size()).append("):\n");
        for (JsonNode agent : agents) {
            String id = agent.path("sessionId").asText("unknown");
            String status = agent.path("status").asText("unknown");
            sb.append("  ").append(id).append(" — ").append(status).append("\n");
        }
        return sb.toString();
    }
}