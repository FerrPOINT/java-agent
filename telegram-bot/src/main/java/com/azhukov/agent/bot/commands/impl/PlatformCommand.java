package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * /platform — Show actual bot status: polling/webhook mode, webhook URL,
 * bot agent name, and allowed users count.
 */
@Component
@RequiredArgsConstructor
public class PlatformCommand implements CommandHandler {

    private final BotProperties properties;

    @Override
    public String name() {
        return "platform";
    }

    @Override
    public String description() {
        return "Show bot platform status";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args != null && !args.isBlank() && !"list".equalsIgnoreCase(args.trim())) {
            return "Usage: /platform";
        }

        StringBuilder sb = new StringBuilder("🤖 Bot platform status:\n\n");

        // Mode: polling or webhook
        String mode = properties.getMode();
        sb.append("  Mode: ").append(mode != null ? mode : "polling").append("\n");

        // Webhook URL (if any)
        String webhookUrl = properties.getWebhook().getUrl();
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            sb.append("  Webhook URL: ").append(webhookUrl).append("\n");
            sb.append("  Webhook path: ").append(properties.getWebhook().getPath()).append("\n");
            sb.append("  Webhook port: ").append(properties.getWebhook().getPort()).append("\n");
        } else {
            sb.append("  Polling timeout: ").append(properties.getPolling().getTimeoutSeconds()).append("s\n");
            sb.append("  Polling limit: ").append(properties.getPolling().getLimit()).append("\n");
        }

        // Bot agent name
        String agentName = properties.getAgentName();
        if (agentName != null && !agentName.isBlank()) {
            sb.append("  Agent name: ").append(agentName).append("\n");
        }

        // Backend URL
        String backendUrl = properties.getBackendUrl();
        if (backendUrl != null && !backendUrl.isBlank()) {
            sb.append("  Backend: ").append(backendUrl).append("\n");
        }

        // Allowed users count
        int allowedIds = properties.getAuth().getAllowedUserIds().size();
        int allowedUsernames = properties.getAuth().getAllowedUsernames().size();
        int allowedChats = properties.getAuth().getAllowedChatIds().size();
        int totalAllowed = allowedIds + allowedUsernames + allowedChats;
        sb.append("  Allowed users: ").append(totalAllowed);
        if (totalAllowed > 0) {
            sb.append(" (").append(allowedIds).append(" IDs, ")
              .append(allowedUsernames).append(" usernames, ")
              .append(allowedChats).append(" chats)");
        }
        if (properties.getAuth().isAllowByDefault()) {
            sb.append(" [open access]");
        }
        sb.append("\n");

        // Default model
        String defaultModel = properties.getDefaultModel();
        if (defaultModel != null && !defaultModel.isBlank()) {
            sb.append("  Model: ").append(defaultModel).append("\n");
        }

        return sb.toString().trim();
    }
}