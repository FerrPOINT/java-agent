package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared mapping utilities between domain messages/tool definitions and
 * LangChain4j equivalents.
 * <p>
 * Extracted from {@link LangChain4jModelClient} so that {@link FallbackModelClient}
 * can reuse the same mapping logic without duplicating it.
 */
public final class LangChain4jMessageMapper {

    private LangChain4jMessageMapper() {}

    public static ChatMessage toLangChain(Message message) {
        return switch (message.role()) {
            case SYSTEM, DEVELOPER -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    var requests = message.toolCalls().stream()
                        .map(c -> ToolExecutionRequest.builder()
                            .id(c.id())
                            .name(c.name())
                            .arguments(LangChain4jModelClient.canonicalizeArguments(c.arguments()))
                            .build())
                        .collect(Collectors.toList());
                    String content = message.content();
                    yield content != null && !content.isBlank()
                        ? AiMessage.from(content, requests)
                        : AiMessage.from(requests);
                }
                yield AiMessage.from(message.content() != null ? message.content() : "");
            }
            case TOOL -> ToolExecutionResultMessage.from(
                message.toolCallId(), null, message.content() != null ? message.content() : "");
        };
    }

    public static ToolSpecification toToolSpec(ToolDefinition definition) {
        return ToolSpecification.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(LangChain4jToolSchemaMapper.toJsonObjectSchema(definition.parameters()))
            .build();
    }
}
