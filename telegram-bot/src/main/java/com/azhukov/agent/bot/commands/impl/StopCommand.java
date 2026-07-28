package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class StopCommand implements CommandHandler {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the current generation";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Stopping current generation...";
    }
}