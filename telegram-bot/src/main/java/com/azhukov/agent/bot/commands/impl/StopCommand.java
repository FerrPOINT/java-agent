package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.springframework.stereotype.Component;

@Component
public class StopCommand implements CommandHandler {

    private final BusySessionHandler busyHandler;

    public StopCommand(BusySessionHandler busyHandler) {
        this.busyHandler = busyHandler;
    }

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
        busyHandler.interrupt(event.chatId());
        return "Stopping current generation...";
    }
}