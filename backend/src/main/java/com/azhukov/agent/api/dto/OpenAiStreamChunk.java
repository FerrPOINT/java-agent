package com.azhukov.agent.api.dto;

import java.util.List;

public record OpenAiStreamChunk(
    String id,
    String object,
    long created,
    String model,
    List<Choice> choices,
    Usage usage
) {
    /** rev-121: terminal-chunk usage (Hermes api_server.py:5539-5548). */
        public record Usage(@com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens") int promptTokens, @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens") int completionTokens, @com.fasterxml.jackson.annotation.JsonProperty("total_tokens") int totalTokens) {}

        public record Choice(int index, Delta delta, @com.fasterxml.jackson.annotation.JsonProperty("finish_reason") String finishReason) {}

        public record Delta(String role, String content, @com.fasterxml.jackson.annotation.JsonProperty("tool_calls") List<ToolCall> toolCalls) {}

        public record ToolCall(String id, String type, Function function) {}

        public record Function(String name, String arguments) {}

    /** Convenience 5-arg ctor (usage omitted) for non-terminal chunks. */
    public OpenAiStreamChunk(String id, String object, Long created, String model, List<Choice> choices) {
        this(id, object, created, model, choices, null);
    }
}
