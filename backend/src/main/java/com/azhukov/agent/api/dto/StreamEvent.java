package com.azhukov.agent.api.dto;

import com.azhukov.agent.core.model.ToolCall;

import java.util.List;
import java.util.UUID;

public record StreamEvent(
    String type,
    String token,
    List<ToolCall> toolCalls,
    String error,
    String modelUsed,
    Integer contextTokens,
    Integer contextLength,
    String toolName,
    String toolResult,
    UUID sessionId
) {
    public StreamEvent(String type, String token, List<ToolCall> toolCalls, String error) {
        this(type, token, toolCalls, error, null, null, null, null, null, null);
    }

    public StreamEvent(String type, String token, List<ToolCall> toolCalls, String error,
                      String modelUsed, Integer contextTokens, Integer contextLength) {
        this(type, token, toolCalls, error, modelUsed, contextTokens, contextLength, null, null, null);
    }

    public StreamEvent(String type, String token, List<ToolCall> toolCalls, String error,
                      String modelUsed, Integer contextTokens, Integer contextLength,
                      String toolName, String toolResult) {
        this(type, token, toolCalls, error, modelUsed, contextTokens, contextLength, toolName, toolResult, null);
    }
}