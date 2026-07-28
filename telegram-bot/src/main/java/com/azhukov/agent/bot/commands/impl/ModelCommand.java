package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.keyboard.InlineKeyboardBuilder;
import com.azhukov.agent.bot.keyboard.KeyboardButton;
import com.azhukov.agent.bot.keyboard.ModelKeyboardBuilder;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelCommand implements CommandHandler {

    private final BotSessionStore store;
    private final BotProperties properties;
    private final ModelKeyboardBuilder modelKeyboardBuilder;
    private final InlineKeyboardBuilder inlineKeyboardBuilder;

    public ModelCommand(BotSessionStore store, BotProperties properties,
                        ModelKeyboardBuilder modelKeyboardBuilder,
                        InlineKeyboardBuilder inlineKeyboardBuilder) {
        this.store = store;
        this.properties = properties;
        this.modelKeyboardBuilder = modelKeyboardBuilder;
        this.inlineKeyboardBuilder = inlineKeyboardBuilder;
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
            // B2.2: Show model keyboard with available models for selection
            // For now, show current model and hint about keyboard
            return "Current model: " + model + "\nUse /model <name> to change, or /model list for options.";
        }
        String trimmed = args.trim();
        if ("list".equalsIgnoreCase(trimmed)) {
            // B2.2: Return text indicating keyboard is available
            // In a full implementation, this would send an inline keyboard via TelegramClient
            return "Model selection keyboard is available. Use the inline buttons to select a model.";
        }
        if ("default".equalsIgnoreCase(trimmed) || "reset".equalsIgnoreCase(trimmed)) {
            store.setModelOverride(session.getId(), null);
            return "Model reset to default.";
        }
        store.setModelOverride(session.getId(), trimmed);
        return "Model set to: " + trimmed;
    }
}