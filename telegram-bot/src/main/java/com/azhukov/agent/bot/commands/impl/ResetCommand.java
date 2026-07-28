package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class ResetCommand implements CommandHandler {

    @Override
    public String name() {
        return "reset";
    }

    @Override
    public String description() {
        return "Reset the current session context";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Session reset. Send a new message.";
    }
}