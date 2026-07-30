package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TitleCommand implements CommandHandler {

    private final BotSessionStore store;

    

    @Override
    public String name() {
        return "title";
    }

    @Override
    public String description() {
        return "Set or show the session title (usage: /title [text])";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            String title = session.getTitle();
            return title != null ? "Current title: " + title : "No title set";
        }
        store.updateTitle(session.getId(), args.trim());
        return "Title set to: " + args.trim();
    }
}