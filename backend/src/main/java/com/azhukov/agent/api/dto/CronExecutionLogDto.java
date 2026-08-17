package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for a cron execution log entry. Mirrors
 * {@link com.azhukov.agent.persistence.entity.CronExecutionLogEntity}.
 */
public record CronExecutionLogDto(
    Long id,
    UUID jobId,
    Instant startedAt,
    Instant finishedAt,
    String status,
    String errorMessage,
    Instant createdAt
) {}