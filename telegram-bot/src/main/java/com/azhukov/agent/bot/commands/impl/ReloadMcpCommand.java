package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.4: /reload_mcp — Call backend POST /api/v1/agent/reload-mcp. Show result.
 */
@Component
public class ReloadMcpCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public ReloadMcpCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "reload_mcp";
    }

    @Override
    public String description() {
        return "Reload MCP servers";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return backendClient.reloadMcp();
    }
}