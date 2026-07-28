package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.7: /personality — Personality system (stub, not available in this build).
 */
@Component
public class PersonalityCommand implements CommandHandler {

    @Override
    public String name() {
        return "personality";
    }

    @Override
    public String description() {
        return "Personality system (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Personality system is not available. Configure agent.name in application.yml.";
    }
}