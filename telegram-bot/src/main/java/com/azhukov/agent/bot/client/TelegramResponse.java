package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramResponse(
    Boolean ok,
    @JsonProperty("error_code") Integer errorCode,
    String description,
    Object result,
    Map<String, JsonNode> parameters
) {

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static TelegramResponse fromJson(
        @JsonProperty("ok") JsonNode ok,
        @JsonProperty("error_code") JsonNode errorCode,
        @JsonProperty("description") String description,
        @JsonProperty("result") Object result,
        @JsonProperty("parameters") Map<String, JsonNode> parameters
    ) {
        // These fields can authorize a retry, so require their wire types before conversion.
        boolean hasOk = ok != null && !ok.isNull();
        boolean hasErrorCode = errorCode != null && !errorCode.isNull();
        if (hasOk && !ok.isBoolean()) {
            throw new IllegalArgumentException("Telegram ok must be a JSON boolean");
        }
        if (hasErrorCode && (!errorCode.isIntegralNumber() || !errorCode.canConvertToInt())) {
            throw new IllegalArgumentException("Telegram error_code must be a JSON integer");
        }
        return new TelegramResponse(hasOk ? ok.booleanValue() : null,
            hasErrorCode ? errorCode.intValue() : null, description, result, parameters);
    }

    /** Compact constructor with null-safe default for parameters. */
    public TelegramResponse(boolean ok, Integer errorCode, String description, Object result) {
        this(ok, errorCode, description, result, null);
    }

    public boolean isSuccess() {
        return Boolean.TRUE.equals(ok);
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

    public Long resultMessageIdAsLong() {
        return deliveredMessageId(result);
    }

    static Long deliveredMessageId(Object message) {
        if (!(message instanceof Map<?, ?> fields)) return null;
        Object id = fields.get("message_id");
        if (!(id instanceof Integer || id instanceof Long)) return null;
        long value = ((Number) id).longValue();
        // Telegram may return zero before a scheduled message is usable.
        return value > 0 ? value : null;
    }
}