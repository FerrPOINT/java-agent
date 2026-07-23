package com.azhukov.agent.core.model;

import java.util.List;
import java.util.Objects;

public record ChatResponse(
    String content,
    List<ToolCall> toolCalls,
    Usage usage,
    String finishReason
) {
    public ChatResponse {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(toolCalls, "toolCalls must not be null");
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content, List.of(), null, "stop");
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}
