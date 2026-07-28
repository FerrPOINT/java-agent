package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class CompressCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public CompressCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "compress";
    }

    @Override
    public String description() {
        return "Compress session context";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) return "No active session.";
        String focus = event.commandArgs();
        return backendClient.compressSession(session.getId().toString(), focus);
    }
}