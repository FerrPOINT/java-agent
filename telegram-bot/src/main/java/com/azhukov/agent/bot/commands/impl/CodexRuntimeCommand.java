package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * /codex_runtime — Show or switch the active model runtime.
 * /codex_runtime              — show current model and available models
 * /codex_runtime <model>      — switch to a different model
 * /codex_runtime list         — list all available models
 *
 * Note: This is a lightweight version of Hermes' codex runtime switching.
 * It uses session.modelOverride for per-session model selection.
 */
@Component
public class CodexRuntimeCommand implements CommandHandler {

    private final BotProperties properties;

    public CodexRuntimeCommand(BotProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "codex_runtime";
    }

    @Override
    public String description() {
        return "Show or switch active model runtime";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return showCurrent(session);
        }

        String sub = args.trim().toLowerCase();

        if (sub.equals("list")) {
            return listModels();
        }

        if (sub.equals("reset") || sub.equals("default")) {
            if (session != null) {
                session.setModelOverride(null);
                return "Model reset to default: " + properties.getDefaultModel();
            }
            return "No active session.";
        }

        // Try to set model override
        String model = args.trim();
        var available = properties.getAvailableModels();
        if (!available.isEmpty() && !available.contains(model)) {
            return "Model \"" + model + "\" not in available models.\n"
                + "Available: " + String.join(", ", available) + "\n"
                + "Use /codex_runtime list to see all models.";
        }

        if (session != null) {
            session.setModelOverride(model);
            return "Runtime switched to: " + model;
        }
        return "No active session.";
    }

    private String showCurrent(BotSessionEntity session) {
        String model = properties.getDefaultModel();
        String sessionModel = session != null ? session.getModelOverride() : null;
        if (sessionModel != null && !sessionModel.isBlank()) {
            model = sessionModel;
        }

        StringBuilder sb = new StringBuilder("Current runtime:\n");
        sb.append("  Model: ").append(model).append("\n");
        sb.append("  Session override: ");
        sb.append(sessionModel != null && !sessionModel.isBlank() ? sessionModel : "none").append("\n");
        sb.append("  Working directory: ").append(properties.getWorkingDirectory()).append("\n");

        if (!properties.getAvailableModels().isEmpty()) {
            sb.append("\nAvailable models: /codex_runtime list");
        }

        sb.append("\nSwitch: /codex_runtime <model>");
        sb.append("\nReset: /codex_runtime reset");
        return sb.toString().trim();
    }

    private String listModels() {
        var models = properties.getAvailableModels();
        if (models.isEmpty()) {
            return "No models configured. Set bot.available-models in application.yml.";
        }

        StringBuilder sb = new StringBuilder("Available models:\n");
        String defaultModel = properties.getDefaultModel();
        for (String m : models) {
            sb.append("  ");
            if (m.equals(defaultModel)) sb.append("(default) ");
            sb.append(m).append("\n");
        }
        sb.append("\nSwitch: /codex_runtime <model>");
        return sb.toString().trim();
    }
}