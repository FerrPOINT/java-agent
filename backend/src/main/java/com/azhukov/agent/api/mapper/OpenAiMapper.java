package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;

/**
 * Maps between domain models and OpenAI-compatible DTOs.
 */
@Mapper(config = com.azhukov.agent.config.MapStructConfig.class, componentModel = "spring")
public interface OpenAiMapper {

    @Mapping(target = "role", source = "role", qualifiedByName = "roleToString")
    @Mapping(target = "content", source = "content")
    @Mapping(target = "toolCalls", source = "toolCalls", qualifiedByName = "toolCallsToOpenAi")
    @Mapping(target = "toolCallId", source = "toolCallId")
    OpenAiChatRequest.OpenAiMessage toOpenAiMessage(Message message);

    default List<OpenAiChatRequest.OpenAiMessage> toOpenAiMessages(List<Message> messages) {
        if (messages == null) {
            return Collections.emptyList();
        }
        return messages.stream().map(this::toOpenAiMessage).toList();
    }

    @Mapping(target = "type", constant = "function")
    @Mapping(target = "function.name", source = "name")
    @Mapping(target = "function.description", source = "description")
    @Mapping(target = "function.parameters", source = "parameters")
    OpenAiChatRequest.OpenAiTool toOpenAiTool(ToolDefinition definition);

    default List<OpenAiChatRequest.OpenAiTool> toOpenAiTools(List<ToolDefinition> definitions) {
        if (definitions == null) {
            return Collections.emptyList();
        }
        return definitions.stream().map(this::toOpenAiTool).toList();
    }

    default com.azhukov.agent.core.model.ChatResponse toChatResponse(OpenAiChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return com.azhukov.agent.core.model.ChatResponse.text("");
        }
        OpenAiChatResponse.Message message = response.choices().get(0).message();
        String content = message.content() != null ? message.content() : "";
        List<ToolCall> toolCalls = openAiToolCallsToDomain(message.toolCalls());
        if (toolCalls.isEmpty()) {
            return com.azhukov.agent.core.model.ChatResponse.text(content);
        }
        return new com.azhukov.agent.core.model.ChatResponse(content, toolCalls);
    }

    @Named("roleToString")
    default String roleToString(Role role) {
        return role == null ? "user" : role.name().toLowerCase();
    }

    @Named("toolCallsToOpenAi")
    default List<OpenAiChatRequest.OpenAiToolCall> toolCallsToOpenAi(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }
        return toolCalls.stream()
            .map(tc -> new OpenAiChatRequest.OpenAiToolCall(
                tc.id(),
                "function",
                new OpenAiChatRequest.OpenAiFunctionCall(tc.name(), tc.arguments())
            ))
            .toList();
    }

    @Named("openAiToolCallsToDomain")
    default List<ToolCall> openAiToolCallsToDomain(List<OpenAiChatResponse.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        return toolCalls.stream()
            .map(tc -> new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()))
            .toList();
    }
}
