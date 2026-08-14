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
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Map;
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
                            .arguments(c.arguments())
                            .build())
                        .collect(Collectors.toList());
                    yield AiMessage.from(requests);
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
            .parameters(toJsonSchema(definition.parameters()))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static JsonObjectSchema toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return JsonObjectSchema.builder().build();
        }
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                builder.addStringProperty(e.getKey(), descriptionOf(e.getValue()));
            }
        }
        if (required != null) {
            builder.required(required);
        }
        return builder.build();
    }

    private static String descriptionOf(Object spec) {
        if (spec instanceof Map m) {
            Object desc = m.get("description");
            return desc != null ? desc.toString() : "";
        }
        return "";
    }
}