package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

@Component
public class TitleCommand implements CommandHandler {

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
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            if (session != null && session.getTitle() != null) {
                return "Current title: " + session.getTitle();
            }
            return "No title set. Usage: /title <text>";
        }
        return "Title set to: " + args;
    }
}