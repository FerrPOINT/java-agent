package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingMemoryDto(
    UUID id,
    String userId,
    String action,
    String target,
    String content,
    String oldText,
    String summary,
    String origin,
    String status,
    Instant createdAt,
    Instant resolvedAt
) {}