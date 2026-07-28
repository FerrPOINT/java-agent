package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class FastCommand implements CommandHandler {

    @Override
    public String name() {
        return "fast";
    }

    @Override
    public String description() {
        return "Toggle fast mode (reduced reasoning for quick replies)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) {
            return "No active session.";
        }
        boolean newState = !session.isFastMode();
        return "Fast mode " + (newState ? "enabled" : "disabled") + ".";
    }
}