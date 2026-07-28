package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class NewSessionCommand implements CommandHandler {

    @Override
    public String name() {
        return "new";
    }

    @Override
    public String description() {
        return "Start a new chat session";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "New session started. Send a message to begin.";
    }
}