package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.3: /credits — Credit balance (stub, not available in this build).
 */
@Component
public class CreditsCommand implements CommandHandler {

    @Override
    public String name() {
        return "credits";
    }

    @Override
    public String description() {
        return "Credit balance (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Credit balance is not available in this build.";
    }
}