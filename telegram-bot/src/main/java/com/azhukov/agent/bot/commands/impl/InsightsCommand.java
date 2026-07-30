package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InsightsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "insights";
    }

    @Override
    public String description() {
        return "Show usage insights";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode insights = backendClient.getInsights();
        if (insights == null || insights.isMissingNode() || insights.isNull()) {
            return "No insights available.";
        }
        StringBuilder sb = new StringBuilder("Usage insights:\n");
        sb.append("  Total tokens: ").append(insights.path("totalTokens").asInt(0)).append("\n");
        sb.append("  Total messages: ").append(insights.path("totalMessages").asInt(0)).append("\n");
        JsonNode byModel = insights.path("byModel");
        if (byModel.isObject() && !byModel.isEmpty()) {
            sb.append("  By model:\n");
            byModel.fields().forEachRemaining(e -> sb.append("    ").append(e.getKey()).append(": ").append(e.getValue().asInt()).append("\n"));
        }
        return sb.toString();
    }
}