package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

@Component
public class FooterCommand implements CommandHandler {

    private final BotSessionStore store;

    public FooterCommand(BotSessionStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "footer";
    }

    @Override
    public String description() {
        return "Toggle runtime footer";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) return "No active session.";
        String args = event.commandArgs();
        if (args != null && !args.isBlank()) {
            String arg = args.trim().toLowerCase();
            switch (arg) {
                case "on" -> {
                    if (session.isFooterEnabled()) return "Footer already enabled.";
                    store.toggleFooter(session.getId());
                    return "Footer enabled.";
                }
                case "off" -> {
                    if (!session.isFooterEnabled()) return "Footer already disabled.";
                    store.toggleFooter(session.getId());
                    return "Footer disabled.";
                }
                case "status" -> {
                    return "Footer: " + (session.isFooterEnabled() ? "on" : "off");
                }
                default -> {
                    return "Usage: /footer on|off|status";
                }
            }
        }
        boolean newState = store.toggleFooter(session.getId());
        return "Footer " + (newState ? "enabled" : "disabled") + ".";
    }
}