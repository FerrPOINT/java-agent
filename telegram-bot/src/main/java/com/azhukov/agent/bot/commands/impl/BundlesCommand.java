package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * A2.6: /bundles — Call backend GET /api/v1/agent/bundles. List installed skill bundles.
 */
@Component
public class BundlesCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public BundlesCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "bundles";
    }

    @Override
    public String description() {
        return "List installed skill bundles";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        JsonNode bundles = backendClient.listBundles();
        if (bundles == null || bundles.isEmpty() || (bundles.isArray() && bundles.size() == 0)) {
            return "No skill bundles installed.";
        }
        StringBuilder sb = new StringBuilder("Installed skill bundles:\n");
        if (bundles.isArray()) {
            for (JsonNode bundle : bundles) {
                if (bundle.isTextual()) {
                    sb.append("  • ").append(bundle.asText()).append("\n");
                } else if (bundle.isObject()) {
                    String name = bundle.path("name").asText(bundle.path("id").asText("unknown"));
                    sb.append("  • ").append(name).append("\n");
                }
            }
        } else {
            sb.append("  ").append(bundles.toString());
        }
        return sb.toString().trim();
    }
}