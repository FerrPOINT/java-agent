package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class UsageCommand implements CommandHandler {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "Show token usage statistics";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Usage statistics will be available here.";
    }
}