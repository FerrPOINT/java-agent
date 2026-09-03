package com.azhukov.agent.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatResponseDto(
    UUID sessionId,
    String content,
    List<String> toolCalls,
    boolean completed,
    boolean memoryUpdated,
    String modelUsed,
    Integer contextTokens,
    Integer contextLength,
    List<Map<String, Object>> messages
) {
    // Backward-compatible constructor (memoryUpdated defaults to false, no metadata)
    public ChatResponseDto(UUID sessionId, String content, List<String> toolCalls, boolean completed) {
        this(sessionId, content, toolCalls, completed, false, null, null, null, null);
    }

    public ChatResponseDto(UUID sessionId, String content, List<String> toolCalls, boolean completed,
                           boolean memoryUpdated) {
        this(sessionId, content, toolCalls, completed, memoryUpdated, null, null, null, null);
    }

    public ChatResponseDto(UUID sessionId, String content, List<String> toolCalls, boolean completed,
                           boolean memoryUpdated, String modelUsed, Integer contextTokens, Integer contextLength) {
        this(sessionId, content, toolCalls, completed, memoryUpdated, modelUsed, contextTokens, contextLength, null);
    }
}
