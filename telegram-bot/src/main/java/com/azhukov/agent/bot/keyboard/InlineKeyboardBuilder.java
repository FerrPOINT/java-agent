package com.azhukov.agent.bot.keyboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a JSON string for Telegram's InlineKeyboardMarkup.
 *
 * <p>The output is suitable for the {@code reply_markup} parameter in
 * Telegram API calls. The JSON structure is:
 * <pre>
 * {"inline_keyboard":[[{"text":"Btn1","callback_data":"cmd:val1"}]]}
 * </pre>
 */
@Component
public class InlineKeyboardBuilder {

    private static final Logger log = LoggerFactory.getLogger(InlineKeyboardBuilder.class);

    private final ObjectMapper objectMapper;

    public InlineKeyboardBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the InlineKeyboardMarkup JSON string.
     *
     * @param buttons a list of rows, each row is a list of KeyboardButton
     * @return JSON string for reply_markup, or empty string if serialization fails
     */
    public String build(List<List<KeyboardButton>> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return "{\"inline_keyboard\":[]}";
        }

        // Build the JSON structure manually using the ObjectMapper for proper escaping
        StringBuilder sb = new StringBuilder();
        sb.append("{\"inline_keyboard\":[");

        for (int rowIdx = 0; rowIdx < buttons.size(); rowIdx++) {
            List<KeyboardButton> row = buttons.get(rowIdx);
            if (rowIdx > 0) sb.append(",");
            sb.append("[");
            for (int btnIdx = 0; btnIdx < row.size(); btnIdx++) {
                KeyboardButton btn = row.get(btnIdx);
                if (btnIdx > 0) sb.append(",");
                sb.append("{\"text\":");
                sb.append(escapeJson(btn.text()));
                sb.append(",\"callback_data\":");
                sb.append(escapeJson(btn.callbackData()));
                sb.append("}");
            }
            sb.append("]");
        }

        sb.append("]}");

        // Validate the generated JSON
        try {
            objectMapper.readTree(sb.toString());
        } catch (JsonProcessingException e) {
            log.warn("Generated invalid keyboard JSON: {}", e.getMessage());
            return "{\"inline_keyboard\":[]}";
        }

        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Fallback manual escaping
            return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
        }
    }
}