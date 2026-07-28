package com.azhukov.agent.api.dto;

import java.util.List;
import java.util.UUID;

public record ChatResponseDto(
    UUID sessionId,
    String content,
    List<String> toolCalls,
    boolean completed,
    boolean memoryUpdated
) {
    // Backward-compatible constructor (memoryUpdated defaults to false)
    public ChatResponseDto(UUID sessionId, String content, List<String> toolCalls, boolean completed) {
        this(sessionId, content, toolCalls, completed, false);
    }
}
