package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Maps between {@link com.azhukov.agent.persistence.entity.SessionEntity} and
 * {@link com.azhukov.agent.core.model.Session}.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface SessionEntityMapper {

    @Mapping(target = "systemPrompt", ignore = true)
    @Mapping(target = "metadata", ignore = true)
    Session toDomain(SessionEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "modelProvider", source = "modelProvider")
    @Mapping(target = "modelName", source = "modelName")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "cliState", ignore = true)
    @Mapping(target = "parentSessionId", ignore = true)
    @Mapping(target = "sessionStatus", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "endReason", ignore = true)
    @Mapping(target = "preview", ignore = true)
    @Mapping(target = "lastActive", ignore = true)
    @Mapping(target = "messageCount", ignore = true)
    @Mapping(target = "subgoal", ignore = true)
    SessionEntity toEntity(Session session);
}
