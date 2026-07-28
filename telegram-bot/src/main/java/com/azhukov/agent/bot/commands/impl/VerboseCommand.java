package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

@Component
public class VerboseCommand implements CommandHandler {

    private final BotSessionStore store;

    public VerboseCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "verbose";
    }

    @Override
    public String description() {
        return "Toggle verbose output mode";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        boolean newState = store.toggleVerbose(session.getId());
        return "Verbose mode " + (newState ? "enabled" : "disabled") + ".";
    }
}