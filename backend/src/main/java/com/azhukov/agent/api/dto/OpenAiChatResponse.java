package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OpenAiChatResponse(
    String id,
    String object,
    long created,
    String model,
    List<Choice> choices,
    Usage usage
) {
    public record Choice(
        int index,
        Message message,
        @JsonProperty("finish_reason") @JsonAlias("finishReason") String finishReason
    ) {}
    public record Message(
        String role,
        String content,
        @JsonProperty("tool_calls") @JsonAlias("toolCalls") List<ToolCall> toolCalls
    ) {}
    public record ToolCall(String id, String type, Function function) {}
    public record Function(String name, String arguments) {}
    public record Usage(
        @JsonProperty("prompt_tokens") @JsonAlias("promptTokens") int promptTokens,
        @JsonProperty("completion_tokens") @JsonAlias("completionTokens") int completionTokens,
        @JsonProperty("total_tokens") @JsonAlias("totalTokens") int totalTokens
    ) {}
}
