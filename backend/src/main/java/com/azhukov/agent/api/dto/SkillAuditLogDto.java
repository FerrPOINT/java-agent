package com.azhukov.agent.api.dto;

import java.time.Instant;

/**
 * DTO for a skill audit log entry — returned by the skill audit endpoint.
 * Hides the JPA entity {@code SkillAuditLogEntity} from the controller/API layer.
 */
public record SkillAuditLogDto(
    Long id,
    String skillName,
    String action,
    String userId,
    String oldValue,
    String newValue,
    Instant timestamp
) {}