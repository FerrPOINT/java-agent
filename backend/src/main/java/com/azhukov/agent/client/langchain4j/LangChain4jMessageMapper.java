package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
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
                    yield AiMessage.builder()
                        .text(content != null && !content.isBlank() ? content : null)
                        .thinking(message.reasoning())
                        .toolExecutionRequests(requests)
                        .build();
                }
                yield AiMessage.builder()
                    .text(message.content() != null ? message.content() : "")
                    .thinking(message.reasoning())
                    .build();
            }
            case TOOL -> ToolExecutionResultMessage.from(
                message.toolCallId(), null, message.content() != null ? message.content() : "");
        };
    }

    static ChatResponse fromLangChain(dev.langchain4j.model.chat.response.ChatResponse response) {
        var message = response.aiMessage();
        List<ToolCall> calls = new ArrayList<>();
        if (message.hasToolExecutionRequests()) {
            for (var request : message.toolExecutionRequests()) {
                String id = request.id();
                if (id == null || id.isBlank()) {
                    id = ToolCall.deterministicCallId(request.name(), request.arguments(), calls.size());
                }
                calls.add(new ToolCall(id, request.name(), request.arguments()));
            }
        }
        var reason = response.finishReason();
        var tokens = response.tokenUsage();
        TokenUsage usage = tokens != null && tokens.outputTokenCount() != null
            ? TokenUsage.of(tokens.inputTokenCount() == null ? 0 : tokens.inputTokenCount(), tokens.outputTokenCount())
            : null;
        String text = message.text();
        return new ChatResponse(text == null || (!calls.isEmpty() && text.isBlank()) ? "" : text,
            calls, reason != null ? reason.name() : null, usage).withReasoning(message.thinking());
    }

    public static ToolSpecification toToolSpec(ToolDefinition definition) {
        return ToolSpecification.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(LangChain4jToolSchemaMapper.toJsonObjectSchema(definition.parameters()))
            .build();
    }
}
