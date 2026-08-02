package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * /reload — Reload skills and MCP servers on the agent backend
 * without restarting the entire process.
 */
@Component
@RequiredArgsConstructor
public class ReloadCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() { return "reload"; }

    @Override
    public String description() { return "Reload skills and MCP servers"; }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return backendClient.reloadAll();
    }
}