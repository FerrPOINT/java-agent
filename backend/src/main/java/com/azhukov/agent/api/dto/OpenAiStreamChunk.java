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
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}

    public record Choice(int index, Delta delta, String finishReason) {}

    public record Delta(String role, String content, List<ToolCall> toolCalls) {}

    public record ToolCall(String id, String type, Function function) {}

    public record Function(String name, String arguments) {}
}
