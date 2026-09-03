package com.azhukov.agent.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for a cron job. Mirrors {@link com.azhukov.agent.persistence.entity.CronJobEntity}
 * but exposes only the API-relevant fields — persistence internals are hidden.
 */
public record CronJobDto(
    UUID id,
    String profile,
    String name,
    String schedule,
    String prompt,
    boolean enabled,
    String deliverTo,
    String skills,
    String contextFrom,
    String monitor,
    String monitorLastHash,
    String monitorLastOutput,
    Instant monitorLastChangedAt,
    boolean continuityEnabled,
    UUID attachedSessionId,
    Integer repeatCount,
    int repeatCompleted,
    String script,
    boolean noAgent,
    String enabledToolsets,
    String workdir,
    String modelProvider,
    String modelName,
    String baseUrl,
    String providerSnapshot,
    String modelSnapshot,
    Instant createdAt,
    Instant lastRunAt,
    Instant nextRunAt,
    String lastStatus,
    String lastError,
    Instant lastErrorAt,
    int consecutiveFailures,
    java.time.Instant lastDeliveredRunAt,
    UUID lastRunSessionId
) {}
