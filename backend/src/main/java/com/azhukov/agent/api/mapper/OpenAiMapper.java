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
        return new com.azhukov.agent.core.model.ChatResponse(content, toolCalls, "TOOL_EXECUTION");
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

    // ── Reverse mappings for ChatCompletionsController ──

    default Message toMessage(OpenAiChatRequest.OpenAiMessage m) {
        if (m == null) return Message.user("");
        String role = m.role() != null ? m.role() : "user";
        return switch (role) {
            case "system" -> Message.system(m.content());
            case "developer" -> Message.developer(m.content());
            case "assistant" -> {
                List<ToolCall> calls = toDomainToolCalls(m.toolCalls());
                yield calls.isEmpty()
                    ? Message.assistant(m.content(), 0)
                    : Message.assistantWithToolCalls(m.content(), calls, 0);
            }
            case "tool" -> Message.toolResult(m.toolCallId(), m.content(), 0);
            default -> Message.user(m.content());
        };
    }

    /** Preserve OpenAI assistant tool calls on API ingress; dropping them makes
     * every following tool result orphaned on the next model request. */
    private static List<ToolCall> toDomainToolCalls(List<OpenAiChatRequest.OpenAiToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
            .filter(tc -> tc != null && tc.function() != null)
            .map(tc -> new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()))
            .toList();
    }

    default ToolDefinition toToolDefinition(OpenAiChatRequest.OpenAiTool tool) {
        if (tool == null || tool.function() == null) return null;
        return new ToolDefinition(
            tool.function().name(),
            tool.function().description(),
            tool.function().parameters()
        );
    }

    default OpenAiChatResponse.ToolCall toOpenAiToolCall(ToolCall tc) {
        return new OpenAiChatResponse.ToolCall(
            tc.id() != null ? tc.id() : java.util.UUID.randomUUID().toString(),
            "function",
            new OpenAiChatResponse.Function(tc.name(), tc.arguments())
        );
    }

    default OpenAiChatResponse toOpenAiResponse(String model, com.azhukov.agent.core.model.ChatResponse response) {
        String content = response.content() != null ? response.content() : "";
        List<OpenAiChatResponse.ToolCall> toolCalls = response.toolCalls() != null
            ? response.toolCalls().stream().map(this::toOpenAiToolCall).toList()
            : List.of();
        OpenAiChatResponse.Message message = toolCalls.isEmpty()
            ? new OpenAiChatResponse.Message("assistant", content, null)
            : new OpenAiChatResponse.Message("assistant", null, toolCalls);
        // rev-95: report the provider-reported usage (Hermes maps input/output/
        // total tokens into the OpenAI usage block). Was hardcoded 0,0,0 — SDKs
        // metering off this endpoint saw zero consumption.
        com.azhukov.agent.core.model.TokenUsage usage = response.usage();
        OpenAiChatResponse.Usage usageDto = usage != null
            ? new OpenAiChatResponse.Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens())
            : new OpenAiChatResponse.Usage(0, 0, 0);
        return new OpenAiChatResponse(
            java.util.UUID.randomUUID().toString(),
            "chat.completion",
            java.time.Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiChatResponse.Choice(0, message, "stop")),
            usageDto
        );
    }
}
