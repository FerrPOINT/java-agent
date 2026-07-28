package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

@Component
public class FastCommand implements CommandHandler {

    private final BotSessionStore store;

    public FastCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "fast";
    }

    @Override
    public String description() {
        return "Toggle fast mode (reduced reasoning for quick replies)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        boolean newState = store.toggleFast(session.getId());
        return "Fast mode " + (newState ? "enabled" : "disabled") + ".";
    }
}