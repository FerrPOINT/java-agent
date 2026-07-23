package com.azhukov.agent.core.model;

import java.util.Objects;

public record Message(
    Role role,
    String content,
    ToolCall toolCall,
    String toolCallId
) {
    public Message {
        Objects.requireNonNull(role, "role must not be null");
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null);
    }

    public static Message assistantToolCall(ToolCall toolCall) {
        return new Message(Role.ASSISTANT, null, toolCall, null);
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, null, toolCallId);
    }
}
