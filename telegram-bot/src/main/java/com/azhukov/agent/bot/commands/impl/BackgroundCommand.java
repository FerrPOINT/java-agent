package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.8: /background &lt;prompt&gt; — Run in background. Call backend POST /api/v1/agent/background.
 */
@Component
public class BackgroundCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public BackgroundCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "background";
    }

    @Override
    public String description() {
        return "Run prompt in background";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String prompt = event.commandArgs();
        if (prompt == null || prompt.isBlank()) {
            return "Usage: /background <prompt>";
        }
        String sessionId = session != null && session.getId() != null
            ? session.getId().toString() : null;
        return backendClient.runBackground(prompt, sessionId);
    }
}