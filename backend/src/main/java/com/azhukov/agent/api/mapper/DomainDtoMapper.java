package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.SkillAuditLogDto;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import org.mapstruct.Mapper;

import java.util.Collections;
import java.util.List;

/**
 * Maps between core domain models and API DTOs.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface DomainDtoMapper {

    SkillAuditLogDto toSkillAuditLogDto(SkillAuditLogEntity entity);

    default SessionSummaryDto toSessionSummaryDto(Session session) {
        if (session == null) {
            return null;
        }
        // M16 fix: source timestamps from the session metadata snapshot when present
        // (set on load from the entity) instead of always returning null.
        java.time.Instant createdAt = parseInstant(session.getMetadata("created_at"));
        java.time.Instant updatedAt = parseInstant(session.getMetadata("updated_at"));
        return new SessionSummaryDto(
            session.id(),
            session.userId(),
            session.title(),
            session.modelProvider(),
            session.modelName(),
            createdAt,
            updatedAt
        );
    }

    private static java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Entity-backed mapping: carries createdAt/updatedAt/parentSessionId that
     * the domain record doesn't hold. Used by session listings so clients can
     * sort by date and follow compression-rotation children.
     */
    default SessionSummaryDto toSessionSummaryDto(com.azhukov.agent.persistence.entity.SessionEntity e) {
        if (e == null) {
            return null;
        }
        return new SessionSummaryDto(
            e.getId(),
            e.getUserId(),
            e.getTitle(),
            e.getModelProvider(),
            e.getModelName(),
            e.getCreatedAt(),
            e.getUpdatedAt(),
            e.getParentSessionId()
        );
    }

    default List<SessionSummaryDto> toSessionSummaryDtoList(List<Session> sessions) {
        if (sessions == null) {
            return Collections.emptyList();
        }
        return sessions.stream()
            .map(this::toSessionSummaryDto)
            .toList();
    }
}
