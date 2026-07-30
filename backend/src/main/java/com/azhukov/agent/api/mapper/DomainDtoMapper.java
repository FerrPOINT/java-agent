package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Maps between core domain models and API DTOs.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface DomainDtoMapper {

    @Mapping(target = "sessionId", source = "sessionId")
    @Mapping(target = "content", source = "response.content")
    @Mapping(target = "toolCalls", source = "response.toolCalls")
    @Mapping(target = "completed", constant = "true")
    @Mapping(target = "memoryUpdated", constant = "false")
    @Mapping(target = "modelUsed", ignore = true)
    @Mapping(target = "contextTokens", ignore = true)
    @Mapping(target = "contextLength", ignore = true)
    ChatResponseDto toChatResponseDto(UUID sessionId, ChatResponse response);

    default List<String> mapToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        return toolCalls.stream()
            .map(tc -> tc.name() + "(" + (tc.arguments() != null ? tc.arguments() : "") + ")")
            .toList();
    }

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
