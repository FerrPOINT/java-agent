package com.azhukov.agent.api.dto;

import java.util.List;
import java.util.Map;

public record OpenAiChatResponse(
    String id,
    String object,
    long created,
    String model,
    List<Choice> choices,
    Usage usage
) {
    public record Choice(int index, Message message, String finishReason) {}
    public record Message(String role, String content, List<ToolCall> toolCalls) {}
    public record ToolCall(String id, String type, Function function) {}
    public record Function(String name, String arguments) {}
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}
