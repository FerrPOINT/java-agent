package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolResultNameResolver {

    private ToolResultNameResolver() {
    }

    public static Map<String, String> collect(List<Message> messages) {
        Map<String, String> namesByCallId = new LinkedHashMap<>();
        if (messages == null || messages.isEmpty()) {
            return namesByCallId;
        }
        for (Message message : messages) {
            if (message == null || message.role() != Role.ASSISTANT || message.toolCalls() == null) {
                continue;
            }
            for (ToolCall toolCall : message.toolCalls()) {
                if (toolCall == null || !hasText(toolCall.name())) {
                    continue;
                }
                for (String id : ToolCall.idVariants(toolCall)) {
                    namesByCallId.putIfAbsent(id, toolCall.name().trim());
                }
            }
        }
        return namesByCallId;
    }

    public static void apply(MessageEntity entity, Message message, Map<String, String> namesByCallId) {
        if (entity == null || message == null || message.role() != Role.TOOL) {
            return;
        }
        if (hasText(entity.getToolCallName()) || !hasText(message.toolCallId()) || namesByCallId == null) {
            return;
        }
        String name = namesByCallId.get(message.toolCallId().trim());
        if (hasText(name)) {
            entity.setToolCallName(name);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
