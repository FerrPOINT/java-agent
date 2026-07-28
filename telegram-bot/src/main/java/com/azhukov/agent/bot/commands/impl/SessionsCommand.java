package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionsCommand implements CommandHandler {

    private final BotSessionStore store;

    public SessionsCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "sessions";
    }

    @Override
    public String description() {
        return "List your chat sessions";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getUserId() == null) {
            return "No active session.";
        }
        List<BotSessionEntity> sessions = store.listByUserId(session.getUserId());
        if (sessions == null || sessions.isEmpty()) {
            return "No sessions found.";
        }
        StringBuilder sb = new StringBuilder("Your sessions:\n");
        for (BotSessionEntity s : sessions) {
            String title = s.getTitle();
            sb.append("  - ")
                .append(title != null ? title : "Untitled")
                .append(" (id: ").append(s.getId()).append(")")
                .append(s.isActive() ? " [active]" : "")
                .append("\n");
        }
        return sb.toString().trim();
    }
}