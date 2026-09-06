package com.azhukov.agent.shared.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire-format chat response shared by all client modules (h10).
 * Field-for-field identical to the backend's
 * {@code com.azhukov.agent.api.dto.ChatResponseDto}; the two are kept in
 * sync deliberately (same field names/order) so clients parse exactly what
 * the backend serializes.
 */
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
