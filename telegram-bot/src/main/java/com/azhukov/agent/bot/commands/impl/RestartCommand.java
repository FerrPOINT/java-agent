package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.3: /restart — Drain active work, send notification, call backend POST /api/v1/agent/restart.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RestartCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "restart";
    }

    @Override
    public String description() {
        return "Restart the agent";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return backendClient.restart();
    }
}