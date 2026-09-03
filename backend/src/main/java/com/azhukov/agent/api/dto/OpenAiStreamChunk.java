package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OpenAiStreamChunk(
    String id,
    String object,
    long created,
    String model,
    List<Choice> choices
) {
    public record Choice(
        int index,
        Delta delta,
        @JsonProperty("finish_reason") @JsonAlias("finishReason") String finishReason
    ) {}

    public record Delta(
        String role,
        String content,
        @JsonProperty("tool_calls") @JsonAlias("toolCalls") List<ToolCall> toolCalls
    ) {}

    public record ToolCall(String id, String type, Function function) {}

    public record Function(String name, String arguments) {}
}
