package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.8: /kanban — Kanban integration (stub, not available in this build).
 */
@Component
public class KanbanCommand implements CommandHandler {

    @Override
    public String name() {
        return "kanban";
    }

    @Override
    public String description() {
        return "Kanban integration (not available)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Kanban integration is not available in this build.";
    }
}