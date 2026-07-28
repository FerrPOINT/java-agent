package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class SkillsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public SkillsCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "List available agent skills";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode node = backendClient.getSkills();
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "No skills available.";
        }
        StringBuilder sb = new StringBuilder("Available skills:\n");
        for (int i = 0; i < node.size(); i++) {
            sb.append("  - ").append(node.get(i).asText()).append("\n");
        }
        return sb.toString().trim();
    }
}