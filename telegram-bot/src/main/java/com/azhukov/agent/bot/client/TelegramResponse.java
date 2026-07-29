package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramResponse(
    boolean ok,
    @JsonProperty("error_code") Integer errorCode,
    String description,
    Object result,
    Map<String, com.fasterxml.jackson.databind.JsonNode> parameters
) {

    /** Compact constructor with null-safe default for parameters. */
    public TelegramResponse(boolean ok, Integer errorCode, String description, Object result) {
        this(ok, errorCode, description, result, null);
    }

    public boolean isSuccess() {
        return ok;
    }

    public String errorMessage() {
        if (description != null) {
            return errorCode != null ? errorCode + ": " + description : description;
        }
        return "Unknown Telegram API error";
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resultAsMap() {
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> resultAsList() {
        if (result instanceof java.util.List<?> list) {
            return (java.util.List<Map<String, Object>>) list;
        }
        return java.util.List.of();
    }

    public String resultMessageId() {
        Map<String, Object> map = resultAsMap();
        Object messageId = map.get("message_id");
        return messageId != null ? messageId.toString() : null;
    }

    public Long resultMessageIdAsLong() {
        String id = resultMessageId();
        if (id == null) return null;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}