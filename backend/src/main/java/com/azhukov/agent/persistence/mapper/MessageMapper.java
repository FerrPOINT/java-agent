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
import java.util.ArrayList;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Maps between {@link MessageEntity} and {@link Message} domain model.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class)
public interface MessageMapper {
    /** Standalone access for bridges that are not Spring-managed. */
    MessageMapper INSTANCE = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);

    ObjectMapper TOOL_CALLS_JSON = new ObjectMapper();
    TypeReference<List<Map<String, Object>>> TOOL_CALLS_TYPE = new TypeReference<>() {};


    default Message toDomain(MessageEntity entity) {
        if (entity == null) {
            return null;
        }
        Role role = stringToRole(entity.getRole());
        boolean isTool = role == Role.TOOL;
        List<ToolCall> persistedToolCalls = isTool ? List.of()
            : ToolCallPersistenceCodec.deserialize(entity.getToolCallsJson());
        boolean malformedSerializedBatch = !isTool
            && ToolCallPersistenceCodec.hasSerializedBatch(entity.getToolCallsJson())
            && persistedToolCalls.isEmpty();
        ToolCall toolCall = isTool || malformedSerializedBatch ? null : (persistedToolCalls.isEmpty()
            ? extractToolCall(entity) : persistedToolCalls.get(0));
        String toolCallId = isTool ? entity.getToolCallId() : null;
        // Hermes parity (agent_runtime_helpers.py #58168): an assistant
        // tool_call must surface in toolCalls (the list) — HistorySanitizer
        // Pass 1 and the OpenAI wire mapper validate tool results against
        // the LIST. A call held only in the singular toolCall field looks
        // like an unanswered tool_call: the sanitizer drops the tool result
        // as an "orphan", strict providers then 400 on the dangling call,
        // and the error is misclassified as CONTEXT_OVERFLOW (fake
        // compression, lost context, incoherent replies).
        List<ToolCall> toolCalls = malformedSerializedBatch ? null : (persistedToolCalls.isEmpty()
            ? (toolCall == null ? null : List.of(toolCall))
            : persistedToolCalls);
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
        List<ToolCall> calls = message.toolCalls() != null && !message.toolCalls().isEmpty()
            ? message.toolCalls()
            : message.toolCall() == null ? List.of() : List.of(message.toolCall());
        if (!calls.isEmpty()) {
            ToolCall first = calls.get(0);
            entity.setToolCallId(first.pairingId());
            entity.setToolCallName(first.name());
            entity.setToolCallArguments(first.arguments());
            entity.setToolResponseItemId(first.responseItemId());
            entity.setToolCallsJson(ToolCallPersistenceCodec.serialize(calls));
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

    default List<ToolCall> parseToolCalls(String rawToolCalls) {
        if (!hasText(rawToolCalls)) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> raw = TOOL_CALLS_JSON.readValue(rawToolCalls, TOOL_CALLS_TYPE);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptyList();
            }
            List<ToolCall> toolCalls = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                Map<String, Object> item = raw.get(i);
                if (item == null) {
                    continue;
                }
                Object rawFunction = item.get("function");
                Map<?, ?> function = rawFunction instanceof Map<?, ?> map ? map : Map.of();
                String name = stringValue(function.get("name"));
                if (!hasText(name)) {
                    name = stringValue(item.get("name"));
                }
                if (!hasText(name)) {
                    continue;
                }
                String arguments = normalizeArguments(
                    function.containsKey("arguments") ? function.get("arguments") : item.get("arguments"));
                String id = stringValue(item.get("id"));
                if (!hasText(id)) {
                    id = ToolCall.deterministicCallId(name, arguments, i);
                }
                toolCalls.add(new ToolCall(id, name, arguments));
            }
            return toolCalls.isEmpty() ? Collections.emptyList() : List.copyOf(toolCalls);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }


    default String normalizeArguments(Object arguments) {
        if (arguments == null) {
            return "{}";
        }
        if (arguments instanceof CharSequence text) {
            String value = text.toString();
            return hasText(value) ? value : "{}";
        }
        try {
            return TOOL_CALLS_JSON.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            String value = String.valueOf(arguments);
            return hasText(value) ? value : "{}";
        }
    }

    default String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    default boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
