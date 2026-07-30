package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * /credits — Show usage-based credit balance (tokens, messages, cost estimate).
 * Unlike Hermes (which shows Nous credit balance), this build shows local usage stats.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CreditsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "credits";
    }

    @Override
    public String description() {
        return "Show usage balance (tokens, messages)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode insights = backendClient.getInsights();
        if (insights == null || insights.isMissingNode() || insights.isNull()) {
            return "Usage data not available. Backend may be offline.";
        }

        int totalTokens = insights.path("totalTokens").asInt(0);
        int totalMessages = insights.path("totalMessages").asInt(0);

        StringBuilder sb = new StringBuilder("📊 Usage balance:\n");
        sb.append("  Total tokens: ").append(totalTokens).append("\n");
        sb.append("  Total messages: ").append(totalMessages).append("\n");

        JsonNode byModel = insights.path("byModel");
        if (byModel.isObject() && !byModel.isEmpty()) {
            sb.append("  By model:\n");
            byModel.fields().forEachRemaining(e ->
                sb.append("    ").append(e.getKey()).append(": ")
                  .append(e.getValue().asInt()).append(" tokens\n"));
        }

        return sb.toString().trim();
    }
}