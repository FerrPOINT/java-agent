package com.azhukov.agent.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ChatResponse(
    String content,
    List<ToolCall> toolCalls
) {
    public ChatResponse {
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content != null ? content : "", Collections.emptyList());
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return new ChatResponse("", toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList());
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
