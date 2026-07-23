package com.azhukov.agent.tools;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface ToolHandler {

    ToolResult execute(String arguments, Message lastAssistant, Session session);

    ObjectMapper TOOL_ARGS_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    static <T> T parseJson(String arguments, Class<T> type) {
        try {
            return TOOL_ARGS_MAPPER.readValue(arguments, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool arguments: " + e.getMessage(), e);
        }
    }
}
