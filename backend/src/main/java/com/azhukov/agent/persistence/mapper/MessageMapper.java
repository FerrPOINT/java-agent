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
import java.util.List;

/**
 * Maps between {@link MessageEntity} and {@link Message} domain model.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class)
public interface MessageMapper {

    default Message toDomain(MessageEntity entity) {
        if (entity == null) {
            return null;
        }
        Role role = stringToRole(entity.getRole());
        boolean isTool = role == Role.TOOL;
        ToolCall toolCall = isTool ? null : extractToolCall(entity);
        String toolCallId = isTool ? entity.getToolCallId() : null;
        // Hermes parity (agent_runtime_helpers.py #58168): an assistant
        // tool_call must surface in toolCalls (the list) — HistorySanitizer
        // Pass 1 and the OpenAI wire mapper validate tool results against
        // the LIST. A call held only in the singular toolCall field looks
        // like an unanswered tool_call: the sanitizer drops the tool result
        // as an "orphan", strict providers then 400 on the dangling call,
        // and the error is misclassified as CONTEXT_OVERFLOW (fake
        // compression, lost context, incoherent replies).
        List<ToolCall> toolCalls = null;
        if (toolCall != null) {
            toolCalls = List.of(toolCall);
        }
        return new Message(role, entity.getContent(), toolCall, toolCalls, toolCallId,
            entity.getTurnIndex(), 0, entity.getCreatedAt());
    }

    default boolean isTool(String role) {
        return "tool".equalsIgnoreCase(role);
    }

    @Mapping(target = "role", source = "role", qualifiedByName = "roleToString")
    @Mapping(target = "sessionId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    // M7: Verified — toEntity() maps tool calls from both Message.toolCall() (single)
    // and Message.toolCalls() (list) into MessageEntity fields (toolCallId, toolCallName,
    // toolCallArguments). Tool call metadata is preserved during mid-turn persistence.
    default MessageEntity toEntity(Message message) {
        if (message == null) {
            return null;
        }
        MessageEntity entity = new MessageEntity();
        entity.setRole(roleToString(message.role()));
        entity.setContent(message.content());
        entity.setTurnIndex(message.turnIndex() != null ? message.turnIndex() : 0);
        if (message.toolCall() != null) {
            entity.setToolCallId(message.toolCall().pairingId());
            entity.setToolCallName(message.toolCall().name());
            entity.setToolCallArguments(message.toolCall().arguments());
            entity.setToolResponseItemId(message.toolCall().responseItemId());
        } else if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ToolCall first = message.toolCalls().get(0);
            entity.setToolCallId(first.pairingId());
            entity.setToolCallName(first.name());
            entity.setToolCallArguments(first.arguments());
            entity.setToolResponseItemId(first.responseItemId());
        } else {
            entity.setToolCallId(message.toolCallId());
        }
        return entity;
    }

    @Named("stringToRole")
    default Role stringToRole(String role) {
        return role == null ? Role.USER : Role.valueOf(role.toUpperCase());
    }

    @Named("roleToString")
    default String roleToString(Role role) {
        return role == null ? null : role.name().toLowerCase();
    }

    @Named("extractToolCall")
    default ToolCall extractToolCall(MessageEntity entity) {
        if (entity.getToolCallName() == null && entity.getToolCallId() == null) {
            return null;
        }
        return new ToolCall(
            entity.getToolCallId(),
            entity.getToolCallId(),
            entity.getToolResponseItemId(),
            entity.getToolCallName(),
            entity.getToolCallArguments()
        );
    }
}
