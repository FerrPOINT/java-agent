package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TodoDto(
    UUID id,
    UUID sessionId,
    String userId,
    String title,
    String status,
    String priority,
    Instant createdAt
) {}