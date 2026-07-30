package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UndoCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "undo";
    }

    @Override
    public String description() {
        return "Undo last N turns";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) return "No active session.";
        int turns = 1;
        String args = event.commandArgs();
        if (args != null && !args.isBlank()) {
            try {
                turns = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                return "Usage: /undo [N]";
            }
            if (turns < 1) turns = 1;
            if (turns > 50) turns = 50;
        }
        return backendClient.undoTurns(session.getId().toString(), turns);
    }
}