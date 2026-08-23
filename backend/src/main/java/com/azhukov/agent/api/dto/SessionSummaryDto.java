package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionSummaryDto(
    UUID id,
    String userId,
    String title,
    String modelProvider,
    String modelName,
    Instant createdAt,
    Instant updatedAt,
    UUID parentSessionId
) {
    /** Backward-compatible constructor for domain-record mapping (no timestamps). */
    public SessionSummaryDto(UUID id, String userId, String title, String modelProvider,
                             String modelName, Instant createdAt, Instant updatedAt) {
        this(id, userId, title, modelProvider, modelName, createdAt, updatedAt, null);
    }
}
