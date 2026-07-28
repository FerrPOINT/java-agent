package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeCommand implements CommandHandler {

    private final BotSessionStore store;

    public ResumeCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "Resume a previous session";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String userId = String.valueOf(event.userId());
        String args = event.commandArgs();

        if (args == null || args.isBlank() || "list".equalsIgnoreCase(args.trim())) {
            List<BotSessionEntity> sessions = store.listByUserId(userId);
            if (sessions.isEmpty()) return "No previous sessions found.";
            StringBuilder sb = new StringBuilder("Recent sessions:\n");
            int count = 0;
            for (BotSessionEntity s : sessions) {
                if (count >= 10) break;
                String title = s.getTitle() != null ? s.getTitle() : "Untitled";
                String active = s.isActive() ? " (active)" : "";
                sb.append(String.format("  %s%s%n", title, active));
                count++;
            }
            sb.append("\nUse /resume <title> to switch to a session.");
            return sb.toString();
        }

        String titlePrefix = args.trim().toLowerCase();
        List<BotSessionEntity> sessions = store.listByUserId(userId);
        for (BotSessionEntity s : sessions) {
            if (s.getTitle() != null && s.getTitle().toLowerCase().startsWith(titlePrefix)) {
                BotSessionEntity resumed = store.resumeSession(s.getId(), userId);
                if (resumed != null) {
                    return "Resumed session: " + (s.getTitle() != null ? s.getTitle() : "Untitled");
                }
            }
        }
        return "No session found matching: " + args;
    }
}