package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class MemoryCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public MemoryCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Show or manage agent memory";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode node = backendClient.getMemory();
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "No memory facts stored.";
        }
        StringBuilder sb = new StringBuilder("Memory facts:\n");
        for (int i = 0; i < node.size(); i++) {
            sb.append("  - ").append(node.get(i).asText()).append("\n");
        }
        return sb.toString().trim();
    }
}