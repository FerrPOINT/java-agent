package com.azhukov.agent.api.dto;

import java.util.UUID;

public record UsageDto(
    UUID sessionId,
    int messageCount,
    int tokenEstimate,
    Double cost,
    java.util.List<String> models
) {
    /** Legacy shape (cost unknown) for older callers/tests. */
    public UsageDto(UUID sessionId, int messageCount, int tokenEstimate) {
        this(sessionId, messageCount, tokenEstimate, null, java.util.List.of());
    }
}