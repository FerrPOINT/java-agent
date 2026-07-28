package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class SessionsCommand implements CommandHandler {

    @Override
    public String name() {
        return "sessions";
    }

    @Override
    public String description() {
        return "List your chat sessions";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Sessions list will be available here.";
    }
}