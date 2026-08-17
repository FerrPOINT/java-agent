package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for a cron job. Mirrors {@link com.azhukov.agent.persistence.entity.CronJobEntity}
 * but exposes only the API-relevant fields — persistence internals are hidden.
 */
public record CronJobDto(
    UUID id,
    String name,
    String schedule,
    String prompt,
    boolean enabled,
    String deliverTo,
    String skills,
    String contextFrom,
    Integer repeatCount,
    int repeatCompleted,
    String script,
    boolean noAgent,
    String enabledToolsets,
    String workdir,
    String modelProvider,
    String modelName,
    String baseUrl,
    Instant createdAt,
    Instant lastRunAt,
    Instant nextRunAt,
    String lastStatus,
    String lastError,
    Instant lastErrorAt,
    int consecutiveFailures
) {}