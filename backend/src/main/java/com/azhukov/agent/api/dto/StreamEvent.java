package com.azhukov.agent.api.dto;

import com.azhukov.agent.core.model.ToolCall;

import java.util.List;

public record StreamEvent(
    String type,
    String token,
    List<ToolCall> toolCalls,
    String error,
    String modelUsed,
    Integer contextTokens,
    Integer contextLength
) {
    public StreamEvent(String type, String token, List<ToolCall> toolCalls, String error) {
        this(type, token, toolCalls, error, null, null, null);
    }
}
