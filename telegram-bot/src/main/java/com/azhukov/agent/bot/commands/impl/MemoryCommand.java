package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class MemoryCommand implements CommandHandler {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Show or manage agent memory";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Memory management will be available here.";
    }
}