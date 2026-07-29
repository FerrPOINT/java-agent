package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class ReloadCommand implements CommandHandler {
    @Override
    public String name() { return "reload"; }
    @Override
    public String description() { return "Reload .env variables into running session"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        // In Spring Boot, env vars are read at startup. A full reload requires restart.
        // For now, suggest /restart for a full reload.
        return "To reload environment variables, use /restart. Spring Boot reads .env at startup.";
    }
}