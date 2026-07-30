package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApproveCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "approve";
    }

    @Override
    public String description() {
        return "Approve pending command";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return backendClient.approve(false, null);
        }
        String arg = args.trim().toLowerCase();
        return switch (arg) {
            case "all" -> backendClient.approve(true, null);
            case "session" -> backendClient.approve(false, "session");
            case "always" -> backendClient.approve(false, "always");
            default -> "Usage: /approve [all|session|always]";
        };
    }
}