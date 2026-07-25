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
    Instant updatedAt
) {}
