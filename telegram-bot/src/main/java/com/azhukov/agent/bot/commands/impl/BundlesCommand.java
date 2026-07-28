package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Stage 14.2: /bundles — List, install, or uninstall skill bundles.
 * <p>
 * Usage:
 * <ul>
 *   <li>{@code /bundles} — list installed bundles</li>
 *   <li>{@code /bundles install <name>} — install a bundle</li>
 *   <li>{@code /bundles uninstall <name>} — uninstall a bundle</li>
 * </ul>
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
        return "List, install, or uninstall skill bundles";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listBundles();
        }

        String[] parts = args.trim().split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();

        switch (subCommand) {
            case "install":
                if (parts.length < 2 || parts[1].isBlank()) {
                    return "Usage: /bundles install <name>";
                }
                return backendClient.installBundle(parts[1].trim());
            case "uninstall":
                if (parts.length < 2 || parts[1].isBlank()) {
                    return "Usage: /bundles uninstall <name>";
                }
                return backendClient.uninstallBundle(parts[1].trim());
            default:
                return "Usage: /bundles [install <name> | uninstall <name>]";
        }
    }

    private String listBundles() {
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