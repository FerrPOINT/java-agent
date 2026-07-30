package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.core.model.Session;
import org.mapstruct.Mapper;

import java.util.Collections;
import java.util.List;

/**
 * Maps between core domain models and API DTOs.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface DomainDtoMapper {

    default SessionSummaryDto toSessionSummaryDto(Session session) {
        if (session == null) {
            return null;
        }
        return new SessionSummaryDto(
            session.id(),
            session.userId(),
            session.title(),
            session.modelProvider(),
            session.modelName(),
            null,
            null
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
