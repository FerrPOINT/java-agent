package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

@Component
public class ModelCommand implements CommandHandler {

    private final BotSessionStore store;
    private final BotProperties properties;

    public ModelCommand(BotSessionStore store, BotProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Set or show the model override (usage: /model [name])";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            String model = session.getModelOverride();
            if (model == null) {
                model = properties.getDefaultModel();
            }
            if (model == null || model.isBlank()) {
                model = "default";
            }
            return "Current model: " + model;
        }
        String trimmed = args.trim();
        if ("default".equalsIgnoreCase(trimmed) || "reset".equalsIgnoreCase(trimmed)) {
            store.setModelOverride(session.getId(), null);
            return "Model reset to default.";
        }
        store.setModelOverride(session.getId(), trimmed);
        return "Model set to: " + trimmed;
    }
}