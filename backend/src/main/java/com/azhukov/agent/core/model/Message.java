package com.azhukov.agent.core.model;

import java.util.List;
import java.util.Objects;

public record Message(
    Role role,
    String content,
    ToolCall toolCall,
    List<ToolCall> toolCalls,
    String toolCallId,
    Integer turnIndex,
    Integer imageCount
) {
    public Message {
        Objects.requireNonNull(role, "role must not be null");
        if (imageCount == null) {
            imageCount = 0;
        }
    }

    // ── Full-arity constructor for backward compatibility (7 fields) ──
    public Message(Role role, String content, ToolCall toolCall, List<ToolCall> toolCalls,
                   String toolCallId, Integer turnIndex) {
        this(role, content, toolCall, toolCalls, toolCallId, turnIndex, 0);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null, 0, 0);
    }

    public static Message userWithImages(String content, int imageCount) {
        return new Message(Role.USER, content, null, null, null, 0, imageCount);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null, 0, 0);
    }

    public static Message developer(String content) {
        return new Message(Role.DEVELOPER, content, null, null, null, 0, 0);
    }

    public static Message assistant(String content, int turnIndex) {
        return new Message(Role.ASSISTANT, content, null, null, null, turnIndex, 0);
    }

    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls, int turnIndex) {
        return new Message(Role.ASSISTANT, content, null, List.copyOf(toolCalls), null, turnIndex, 0);
    }

    public static Message assistantToolCalls(List<ToolCall> toolCalls, int turnIndex) {
        return new Message(Role.ASSISTANT, null, null, List.copyOf(toolCalls), null, turnIndex, 0);
    }

    public static Message toolResult(String toolCallId, String content, int turnIndex) {
        return new Message(Role.TOOL, content, null, null, toolCallId, turnIndex, 0);
    }

    public static Message withContent(Message message, String content) {
        return new Message(message.role(), content, message.toolCall(), message.toolCalls(),
            message.toolCallId(), message.turnIndex(), message.imageCount());
    }

    public static Message withImageCount(Message message, int imageCount) {
        return new Message(message.role(), message.content(), message.toolCall(), message.toolCalls(),
            message.toolCallId(), message.turnIndex(), imageCount);
    }
}