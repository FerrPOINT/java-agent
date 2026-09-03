package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.api.OpenAiContentNormalizer;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        java.util.ArrayList<OpenAiChatRequest.OpenAiToolCall> mapped = new java.util.ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            mapped.add(new OpenAiChatRequest.OpenAiToolCall(
                safeToolCallId(tc, i),
                "function",
                new OpenAiChatRequest.OpenAiFunctionCall(tc.name(), tc.arguments())
            ));
        }
        return mapped;
    }

    @Named("openAiToolCallsToDomain")
    default List<ToolCall> openAiToolCallsToDomain(List<OpenAiChatResponse.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.ArrayList<ToolCall> mapped = new java.util.ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            OpenAiChatResponse.ToolCall tc = toolCalls.get(i);
            if (tc == null || tc.function() == null || !hasText(tc.function().name())) {
                continue;
            }
            String name = tc.function().name().trim();
            String args = normalizeArguments(tc.function().arguments());
            String id = hasText(tc.id()) ? tc.id().trim() : ToolCall.deterministicCallId(name, args, i);
            mapped.add(new ToolCall(id, name, args));
        }
        return mapped;
    }

    // ── Reverse mappings for ChatCompletionsController ──

    default Message toMessage(OpenAiChatRequest.OpenAiMessage m) {
        if (m == null) return Message.user("");
        String role = m.role() != null ? m.role() : "user";
        if ("system".equals(role)) {
            return Message.system(OpenAiContentNormalizer.normalizeSystemText(m.content()));
        }
        if ("developer".equals(role)) {
            return Message.developer(OpenAiContentNormalizer.normalizeSystemText(m.content()));
        }
        OpenAiContentNormalizer.NormalizedConversationContent normalized =
            OpenAiContentNormalizer.normalizeConversationContent(m.content());
        String content = normalized.text();
        int imageCount = normalized.imageCount();
        return switch (role) {
            case "assistant" -> {
                List<ToolCall> toolCalls = openAiToolCallsToDomainRequest(m.toolCalls());
                if (!toolCalls.isEmpty()) {
                    yield withImageCount(Message.assistantWithToolCalls(content, toolCalls, 0), imageCount);
                }
                yield withImageCount(Message.assistant(content, 0), imageCount);
            }
            case "tool" -> withImageCount(Message.toolResult(m.toolCallId(), content, 0), imageCount);
            default -> imageCount > 0 ? Message.userWithImages(content, imageCount) : Message.user(content);
        };
    }

    default Message withImageCount(Message message, int imageCount) {
        return imageCount > 0 ? Message.withImageCount(message, imageCount) : message;
    }

    default List<ToolCall> openAiToolCallsToDomainRequest(List<OpenAiChatRequest.OpenAiToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.ArrayList<ToolCall> mapped = new java.util.ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            OpenAiChatRequest.OpenAiToolCall tc = toolCalls.get(i);
            if (tc == null || tc.function() == null || !hasText(tc.function().name())) {
                continue;
            }
            String name = tc.function().name().trim();
            String args = normalizeArguments(tc.function().arguments());
            String id = hasText(tc.id()) ? tc.id().trim() : ToolCall.deterministicCallId(name, args, i);
            mapped.add(new ToolCall(id, name, args));
        }
        return mapped;
    }

    default ToolDefinition toToolDefinition(OpenAiChatRequest.OpenAiTool tool) {
        if (tool == null || tool.function() == null) return null;
        if (tool.function().name() == null || tool.function().name().isBlank()) return null;
        Map<String, Object> parameters = tool.function().parameters() != null
            ? tool.function().parameters()
            : Map.of("type", "object", "properties", Map.of(), "required", List.of());
        return new ToolDefinition(
            tool.function().name().trim(),
            tool.function().description() != null ? tool.function().description() : "",
            parameters
        );
    }

    default OpenAiChatResponse.ToolCall toOpenAiToolCall(ToolCall tc) {
        return toOpenAiToolCall(tc, 0);
    }

    default OpenAiChatResponse.ToolCall toOpenAiToolCall(ToolCall tc, int index) {
        return new OpenAiChatResponse.ToolCall(
            safeToolCallId(tc, index),
            "function",
            new OpenAiChatResponse.Function(tc.name(), tc.arguments())
        );
    }

    default OpenAiChatResponse toOpenAiResponse(String model, com.azhukov.agent.core.model.ChatResponse response) {
        String content = response.content() != null ? response.content() : "";
        java.util.ArrayList<OpenAiChatResponse.ToolCall> toolCalls = new java.util.ArrayList<>();
        if (response.toolCalls() != null) {
            for (int i = 0; i < response.toolCalls().size(); i++) {
                toolCalls.add(toOpenAiToolCall(response.toolCalls().get(i), i));
            }
        }
        OpenAiChatResponse.Message message = toolCalls.isEmpty()
            ? new OpenAiChatResponse.Message("assistant", content, null)
            : new OpenAiChatResponse.Message("assistant", content.isBlank() ? null : content, toolCalls);
        String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
        return new OpenAiChatResponse(
            java.util.UUID.randomUUID().toString(),
            "chat.completion",
            java.time.Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiChatResponse.Choice(0, message, finishReason)),
            new OpenAiChatResponse.Usage(0, 0, 0)
        );
    }

    private String normalizeArguments(String arguments) {
        return hasText(arguments) ? arguments : "{}";
    }

    private String safeToolCallId(ToolCall tc, int index) {
        String id = tc.id();
        if (hasText(id)) {
            return id.trim();
        }
        return ToolCall.deterministicCallId(tc.name(), normalizeArguments(tc.arguments()), index);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
