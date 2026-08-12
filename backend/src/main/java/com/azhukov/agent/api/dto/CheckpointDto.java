package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CheckpointDto(
    UUID id,
    String description,
    int fileCount,
    long totalSizeBytes,
    Instant createdAt
) {}