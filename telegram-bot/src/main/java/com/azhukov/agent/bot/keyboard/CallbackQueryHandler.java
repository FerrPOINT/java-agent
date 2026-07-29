package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Handles callback_query {@link UpdateEvent}s from inline keyboard presses.
 * Parses callbackData in the format "command:value", routes to the
 * appropriate action, and answers the callback query via TelegramClient.
 * <p>
 * Supported callbacks:
 * <ul>
 *   <li>{@code mp:<model>} — set model override for the session</li>
 *   <li>{@code mpp:<page>} — show model keyboard page N</li>
 *   <li>{@code pp:<slug>} — show models for the selected provider</li>
 *   <li>{@code pp:back} — go back to provider list</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallbackQueryHandler {

    private final TelegramClient telegramClient;
    private final ProviderKeyboardBuilder providerKeyboardBuilder;
    private final ModelKeyboardBuilder modelKeyboardBuilder;
    private final InlineKeyboardBuilder inlineKeyboardBuilder;
    private final BotSessionStore sessionStore;
    private final BotProperties properties;

    /**
     * Handles a callback_query UpdateEvent.
     *
     * @param event the callback_query UpdateEvent
     * @return a response string, or null if not a callback query
     */
    public String handle(UpdateEvent event) {
        if (event == null || event.type() != UpdateEvent.Type.CALLBACK_QUERY) {
            return null;
        }

        String callbackQueryId = event.callbackQueryId();
        String callbackData = event.callbackData();
        long chatId = event.chatId();

        if (callbackData == null || callbackData.isBlank()) {
            answer(callbackQueryId, "Unknown action", false);
            return null;
        }

        log.debug("Handling callback: id={}, data={}, chatId={}", callbackQueryId, callbackData, chatId);

        String command;
        String value;
        int colonIdx = callbackData.indexOf(':');
        if (colonIdx >= 0) {
            command = callbackData.substring(0, colonIdx);
            value = callbackData.substring(colonIdx + 1);
        } else {
            command = callbackData;
            value = "";
        }

        String response = route(command, value, chatId, event.userId());

        String answerText = response != null ? response : "OK";
        answer(callbackQueryId, answerText, false);

        return response;
    }

    private String route(String command, String value, long chatId, long userId) {
        if (command == null || command.isBlank()) {
            return "Unknown command";
        }

        return switch (command) {
            case "mp" -> handleModelSelect(value, chatId, userId);
            case "mpp" -> handleModelPage(value, chatId);
            case "pp" -> handleProviderSelect(value, chatId);
            default -> "Unknown action: " + command;
        };
    }

    /**
     * mp:<model> — set the model override for the user's session.
     */
    private String handleModelSelect(String model, long chatId, long userId) {
        if (model == null || model.isBlank()) {
            return "No model specified";
        }
        BotSessionEntity session = sessionStore.resolveOrCreate(String.valueOf(userId), String.valueOf(chatId), "");
        if (session == null || session.getId() == null) {
            return "No active session";
        }
        sessionStore.setModelOverride(session.getId(), model);
        return "Model set to: " + model;
    }

    /**
     * mpp:<page> — show model keyboard for the given page.
     */
    private String handleModelPage(String pageStr, long chatId) {
        int page;
        try {
            page = Integer.parseInt(pageStr);
        } catch (NumberFormatException e) {
            page = 0;
        }
        List<String> models = properties.getAvailableModels();
        if (models == null || models.isEmpty()) {
            return "No models configured";
        }
        var rows = modelKeyboardBuilder.build(models, page);
        String markup = inlineKeyboardBuilder.build(rows);
        telegramClient.sendMessage(chatId, "Select a model (page " + (page + 1) + "):", null, null, markup);
        return null; // message already sent
    }

    /**
     * pp:<slug> — show models for the selected provider, or go back to provider list.
     */
    private String handleProviderSelect(String slug, long chatId) {
        if ("back".equals(slug)) {
            // Show provider list
            Map<String, String> providers = providerKeyboardBuilder.defaultProviders();
            var rows = providerKeyboardBuilder.buildProviders(providers);
            String markup = inlineKeyboardBuilder.build(rows);
            telegramClient.sendMessage(chatId, "Select a provider:", null, null, markup);
            return null;
        }

        // Show models for the selected provider
        // For now, use the global available models list — in a real implementation
        // this would fetch models from the backend for the specific provider
        List<String> models = properties.getAvailableModels();
        if (models == null || models.isEmpty()) {
            return "No models configured for provider: " + slug;
        }
        var rows = providerKeyboardBuilder.buildModels(models);
        String markup = inlineKeyboardBuilder.build(rows);
        telegramClient.sendMessage(chatId, "Models for " + slug + ":", null, null, markup);
        return null;
    }

    private void answer(String callbackQueryId, String text, boolean showAlert) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            log.warn("Cannot answer callback query: missing callbackQueryId");
            return;
        }
        telegramClient.answerCallbackQuery(callbackQueryId, text, showAlert);
    }
}