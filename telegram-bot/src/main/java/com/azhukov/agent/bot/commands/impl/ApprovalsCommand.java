package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hermes parity (/approvals): show the persistent dangerous-command
 * approval mode. Changing the mode mutates profile-wide security policy —
 * read-only from the bot; the mode is configured via the backend
 * (agent.security.approvals-enabled).
 */
@Component
@RequiredArgsConstructor
public class ApprovalsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() {
        return "approvals";
    }

    @Override
    public String description() {
        return "Show the dangerous-command approval mode";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        // Read the effective state from the backend's capabilities endpoint.
        JsonNode caps = backendClient.suggestionGet("/v1/capabilities");
        boolean approvalsEnabled = caps != null
            && caps.path("features").path("approval_events").asBoolean(false);
        String mode = approvalsEnabled ? "manual" : "off";
        return "Approval mode: " + mode
            + " (persistent backend setting: agent.security.approvals-enabled)."
            + (approvalsEnabled
                ? "\nDangerous commands wait for /approve or /deny."
                : "\nApproval gating is disabled — dangerous commands run without confirmation.");
    }
}
