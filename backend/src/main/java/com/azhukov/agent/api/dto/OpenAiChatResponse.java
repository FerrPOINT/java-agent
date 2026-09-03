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
        public record Choice(int index, Message message, @com.fasterxml.jackson.annotation.JsonProperty("finish_reason") String finishReason) {}
        public record Message(String role, String content, @com.fasterxml.jackson.annotation.JsonProperty("tool_calls") List<ToolCall> toolCalls) {}
        public record ToolCall(String id, String type, Function function) {}
        public record Function(String name, String arguments) {}
        public record Usage(@com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens, @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens, @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") int totalTokens) {}
}
