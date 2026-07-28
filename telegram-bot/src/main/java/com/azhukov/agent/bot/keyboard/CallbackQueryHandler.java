package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles callback_query {@link UpdateEvent}s from inline keyboard presses.
 * Parses callbackData in the format "command:value", routes to the
 * appropriate action, and answers the callback query via TelegramClient.
 */
@Service
public class CallbackQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackQueryHandler.class);

    private final TelegramClient telegramClient;

    public CallbackQueryHandler(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /**
     * Handles a callback_query UpdateEvent. Parses the callback data,
     * answers the callback query, and returns a response string.
     *
     * @param event the callback_query UpdateEvent
     * @return a response string (e.g. a message to send to the chat), or
     *         null if the event is not a callback query or handling failed
     */
    public String handle(UpdateEvent event) {
        if (event == null || event.type() != UpdateEvent.Type.CALLBACK_QUERY) {
            return null;
        }

        String callbackQueryId = event.callbackQueryId();
        String callbackData = event.callbackData();

        if (callbackData == null || callbackData.isBlank()) {
            answer(callbackQueryId, "Unknown action", false);
            return null;
        }

        log.debug("Handling callback: id={}, data={}", callbackQueryId, callbackData);

        // Parse "command:value" format
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

        String response = route(command, value);

        // Answer the callback query
        String answerText = response != null ? response : "OK";
        answer(callbackQueryId, answerText, false);

        return response;
    }

    /**
     * Routes the parsed command and value to the appropriate action.
     * Currently supports a few basic commands. Override or extend to add more.
     *
     * @param command the command part of callbackData
     * @param value   the value part of callbackData
     * @return a response string for the user
     */
    private String route(String command, String value) {
        if (command == null || command.isBlank()) {
            return "Unknown command";
        }

        return switch (command) {
            case "model" -> "Model switched to " + value;
            case "mode" -> "Mode set to " + value;
            case "confirm" -> "Confirmed: " + value;
            case "cancel" -> "Action cancelled";
            case "select" -> "Selected: " + value;
            case "mp" -> "Model switched to " + value;
            case "pp" -> "Provider selected: " + value;
            case "mpp" -> "Model page " + value;
            default -> "Unknown action: " + command;
        };
    }

    private void answer(String callbackQueryId, String text, boolean showAlert) {
        if (callbackQueryId == null || callbackQueryId.isBlank()) {
            log.warn("Cannot answer callback query: missing callbackQueryId");
            return;
        }
        telegramClient.answerCallbackQuery(callbackQueryId, text, showAlert);
    }
}