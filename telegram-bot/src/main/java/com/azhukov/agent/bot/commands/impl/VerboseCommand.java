package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class VerboseCommand implements CommandHandler {

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
        if (session == null) {
            return "No active session.";
        }
        boolean newState = !session.isVerboseMode();
        return "Verbose mode " + (newState ? "enabled" : "disabled") + ".";
    }
}