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

    @Mapping(target = "systemPrompt", source = "systemPrompt")
    // M16 fix: carry entity timestamps into the domain record so DTO mapping can
    // surface real createdAt/updatedAt instead of nulls.
    @Mapping(target = "metadata", expression = "java(timestampMetadata(entity))")
    Session toDomain(SessionEntity entity);

    /** M16: snapshot createdAt/updatedAt into metadata (Instant.toString round-trips). */
    default java.util.Map<String, String> timestampMetadata(SessionEntity entity) {
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        if (entity.getCreatedAt() != null) {
            meta.put("created_at", entity.getCreatedAt().toString());
        }
        if (entity.getUpdatedAt() != null) {
            meta.put("updated_at", entity.getUpdatedAt().toString());
        }
        return java.util.Map.copyOf(meta);
    }

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "modelProvider", source = "modelProvider")
    @Mapping(target = "modelName", source = "modelName")
    @Mapping(target = "systemPrompt", source = "systemPrompt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "cliState", ignore = true)
    @Mapping(target = "parentSessionId", ignore = true)
    @Mapping(target = "cwd", ignore = true)
    @Mapping(target = "gitRepoRoot", ignore = true)
    @Mapping(target = "sessionStatus", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "profile", ignore = true)
    @Mapping(target = "endReason", ignore = true)
    @Mapping(target = "preview", ignore = true)
    @Mapping(target = "lastActive", ignore = true)
    @Mapping(target = "messageCount", ignore = true)
    @Mapping(target = "pinned", ignore = true)
    @Mapping(target = "archived", ignore = true)
    @Mapping(target = "hidden", ignore = true)
    @Mapping(target = "unread", ignore = true)
    @Mapping(target = "subgoal", ignore = true)
    SessionEntity toEntity(Session session);
}
