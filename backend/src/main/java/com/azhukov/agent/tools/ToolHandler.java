package com.azhukov.agent.tools;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

public interface ToolHandler {

    ToolResult execute(String arguments, Message lastAssistant, Session session);

    static <T> T parseJson(String arguments, Class<T> type) {
        try {
            return new ObjectMapper().readValue(arguments, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tool arguments: " + e.getMessage(), e);
        }
    }
}
