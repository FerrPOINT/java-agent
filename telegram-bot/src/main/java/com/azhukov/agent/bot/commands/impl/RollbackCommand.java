package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.2: /rollback — Filesystem rollback (stub, not available in this build).
 */
@Component
public class RollbackCommand implements CommandHandler {

    @Override
    public String name() {
        return "rollback";
    }

    @Override
    public String description() {
        return "Filesystem rollback (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Filesystem rollback is not available. Use /undo for conversation rollback.";
    }
}