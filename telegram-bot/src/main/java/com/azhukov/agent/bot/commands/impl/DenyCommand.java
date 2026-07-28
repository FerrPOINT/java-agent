package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class DenyCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public DenyCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "deny";
    }

    @Override
    public String description() {
        return "Deny pending command";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args != null && "all".equalsIgnoreCase(args.trim())) {
            return backendClient.deny(true);
        }
        return backendClient.deny(false);
    }
}