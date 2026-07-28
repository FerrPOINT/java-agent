package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReasoningCommand implements CommandHandler {

    private static final List<String> LEVELS = List.of("off", "low", "medium", "high");

    private final BotSessionStore store;

    public ReasoningCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "reasoning";
    }

    @Override
    public String description() {
        return "Set reasoning level (usage: /reasoning off|low|medium|high)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            String current = session.getReasoningLevel();
            return "Current reasoning level: " + (current != null ? current : "medium")
                + "\nAvailable: " + String.join(", ", LEVELS);
        }
        String level = args.trim().toLowerCase();
        if (!LEVELS.contains(level)) {
            return "Invalid level. Available: " + String.join(", ", LEVELS);
        }
        store.setReasoningLevel(session.getId(), level);
        return "Reasoning level set to: " + level;
    }
}