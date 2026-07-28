package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record MemoryDto(
    UUID id,
    String userId,
    String category,
    String fact,
    String target,
    Instant createdAt
) {}