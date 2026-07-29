package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.keyboard.InlineKeyboardBuilder;
import com.azhukov.agent.bot.keyboard.KeyboardButton;
import com.azhukov.agent.bot.keyboard.ModelKeyboardBuilder;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ModelCommand implements CommandHandler {

    private final BotSessionStore store;
    private final BotProperties properties;
    private final ModelKeyboardBuilder modelKeyboardBuilder;
    private final InlineKeyboardBuilder inlineKeyboardBuilder;
    private final TelegramClient telegramClient;

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Set or show the model override (usage: /model [name|list])";
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
            return "Current model: " + model + "\nUse /model <name> to change, or /model list for options.";
        }
        String trimmed = args.trim();
        if ("list".equalsIgnoreCase(trimmed)) {
            return sendModelKeyboard(event.chatId());
        }
        if ("default".equalsIgnoreCase(trimmed) || "reset".equalsIgnoreCase(trimmed)) {
            store.setModelOverride(session.getId(), null);
            return "Model reset to default.";
        }
        store.setModelOverride(session.getId(), trimmed);
        return "Model set to: " + trimmed;
    }

    /**
     * B2.2: Send a paginated inline keyboard for model selection.
     * If no available models are configured, falls back to a text hint.
     */
    private String sendModelKeyboard(long chatId) {
        List<String> models = properties.getAvailableModels();
        if (models == null || models.isEmpty()) {
            return "No models configured. Set bot.available-models in application.yml or use /model <name>.";
        }

        List<List<KeyboardButton>> rows = modelKeyboardBuilder.build(models, 0);
        String replyMarkup = inlineKeyboardBuilder.build(rows);
        boolean ok = telegramClient.sendMessage(
            chatId,
            "Select a model:",
            null, null, replyMarkup
        ).isPresent();

        if (!ok) {
            log.warn("Failed to send model keyboard to chat {}", chatId);
            return "Failed to show model keyboard. Use /model <name> instead.";
        }
        // Message already sent with keyboard — return empty so handleCommand doesn't send again
        return "";
    }
}