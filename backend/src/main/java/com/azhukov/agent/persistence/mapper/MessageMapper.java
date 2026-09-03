package com.azhukov.agent.persistence.mapper;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps between {@link MessageEntity} and {@link Message} domain model.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class)
public interface MessageMapper {

    ObjectMapper TOOL_CALLS_JSON = new ObjectMapper();
    TypeReference<List<Map<String, Object>>> TOOL_CALLS_TYPE = new TypeReference<>() {};

    default Message toDomain(MessageEntity entity) {
        if (entity == null) {
            return null;
        }
        Role role = stringToRole(entity.getRole());
        boolean isTool = role == Role.TOOL;
        List<ToolCall> toolCalls = isTool ? Collections.emptyList() : parseToolCalls(entity.getToolCalls());
        ToolCall toolCall = !toolCalls.isEmpty() ? toolCalls.get(0) : isTool ? null : extractToolCall(entity);
        String toolCallId = isTool ? entity.getToolCallId() : null;
        if (toolCalls.isEmpty() && toolCall != null) {
            toolCalls = List.of(toolCall);
        }
        return new Message(role, entity.getContent(), toolCall, toolCalls, toolCallId,
            entity.getTurnIndex(), entity.getImageCount(), entity.getCreatedAt());
    }

    default boolean isTool(String role) {
        return "tool".equalsIgnoreCase(role);
    }

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
        entity.setImageCount(message.imageCount() != null ? message.imageCount() : 0);
        List<ToolCall> toolCalls = effectiveToolCalls(message);
        if (!toolCalls.isEmpty()) {
            ToolCall first = toolCalls.get(0);
            entity.setToolCallId(first.id());
            entity.setToolCallName(first.name());
            entity.setToolCallArguments(first.arguments());
            entity.setToolCalls(serializeToolCalls(toolCalls));
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
        if (!hasText(entity.getToolCallName()) || !hasText(entity.getToolCallId())) {
            return null;
        }
        return new ToolCall(
            entity.getToolCallId(),
            entity.getToolCallName(),
            normalizeArguments(entity.getToolCallArguments())
        );
    }

    default List<ToolCall> effectiveToolCalls(Message message) {
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            return message.toolCalls();
        }
        if (message.toolCall() != null) {
            return List.of(message.toolCall());
        }
        return Collections.emptyList();
    }

    default String serializeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> wire = new ArrayList<>(toolCalls.size());
        for (ToolCall toolCall : toolCalls) {
            if (toolCall == null || !hasText(toolCall.name())) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolCall.name().trim());
            function.put("arguments", normalizeArguments(toolCall.arguments()));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", hasText(toolCall.id()) ? toolCall.id().trim() : "");
            item.put("type", "function");
            item.put("function", function);
            wire.add(item);
        }
        if (wire.isEmpty()) {
            return null;
        }
        try {
            return TOOL_CALLS_JSON.writeValueAsString(wire);
        } catch (JsonProcessingException e) {
            return null;
        }
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
