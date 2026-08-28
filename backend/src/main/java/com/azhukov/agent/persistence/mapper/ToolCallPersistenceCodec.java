package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Serializes the complete assistant tool-call batch for durable replay. */
public final class ToolCallPersistenceCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<ToolCall>> TOOL_CALL_LIST = new TypeReference<>() {};

    private ToolCallPersistenceCodec() {}

    public static String serialize(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(calls);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize tool-call batch", e);
        }
    }

    public static List<ToolCall> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ToolCall> calls = OBJECT_MAPPER.readValue(json, TOOL_CALL_LIST);
            return calls == null ? List.of() : List.copyOf(calls);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot deserialize tool-call batch", e);
        }
    }
}