package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class ContextCommand implements CommandHandler {

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "Show current context size and details";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Context information will be available here.";
    }
}