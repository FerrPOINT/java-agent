package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.9: /goal — Goal management (stub, not available in this build).
 */
@Component
public class GoalCommand implements CommandHandler {

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "Goal management (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Goal management is not available in this build.";
    }
}