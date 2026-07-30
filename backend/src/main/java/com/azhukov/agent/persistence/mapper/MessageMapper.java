package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;

/**
 * Maps between {@link MessageEntity} and {@link Message} domain model.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class)
public interface MessageMapper {

    @Mapping(target = "role", source = "role", qualifiedByName = "stringToRole")
    @Mapping(target = "toolCall", source = ".", qualifiedByName = "extractToolCall")
    @Mapping(target = "toolCalls", ignore = true)
    Message toDomain(MessageEntity entity);

    @Mapping(target = "role", source = "role", qualifiedByName = "roleToString")
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    default MessageEntity toEntity(Message message) {
        if (message == null) {
            return null;
        }
        MessageEntity entity = new MessageEntity();
        entity.setRole(roleToString(message.role()));
        entity.setContent(message.content());
        entity.setTurnIndex(message.turnIndex() != null ? message.turnIndex() : 0);
        if (message.toolCall() != null) {
            entity.setToolCallId(message.toolCall().id());
            entity.setToolCallName(message.toolCall().name());
            entity.setToolCallArguments(message.toolCall().arguments());
        } else if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ToolCall first = message.toolCalls().get(0);
            entity.setToolCallId(first.id());
            entity.setToolCallName(first.name());
            entity.setToolCallArguments(first.arguments());
        } else {
            entity.setToolCallId(message.toolCallId());
        }
        return entity;
    }

    @Named("stringToRole")
    default Role stringToRole(String role) {
        return role == null ? Role.USER : Role.valueOf(role);
    }

    @Named("roleToString")
    default String roleToString(Role role) {
        return role == null ? null : role.name().toLowerCase();
    }

    @Named("extractToolCall")
    default ToolCall extractToolCall(MessageEntity entity) {
        if (entity.getToolCallId() == null) {
            return null;
        }
        return new ToolCall(
            entity.getToolCallId(),
            entity.getToolCallName(),
            entity.getToolCallArguments()
        );
    }
}
