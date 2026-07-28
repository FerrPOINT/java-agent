package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.10: /subgoal — Subgoal management (stub, not available in this build).
 */
@Component
public class SubgoalCommand implements CommandHandler {

    @Override
    public String name() {
        return "subgoal";
    }

    @Override
    public String description() {
        return "Subgoal management (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Subgoal management is not available in this build.";
    }
}