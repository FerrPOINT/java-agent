package com.azhukov.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public record OpenAiChatRequest(
    @NotBlank String model,
    @NotEmpty List<OpenAiMessage> messages,
    List<OpenAiTool> tools,
    Double temperature,
    Integer maxTokens,
    Boolean stream
) {
    public record OpenAiMessage(String role, String content, List<OpenAiToolCall> toolCalls, String toolCallId) {}

    public record OpenAiTool(String type, OpenAiFunction function) {}
    public record OpenAiFunction(String name, String description, Map<String, Object> parameters) {}
    public record OpenAiToolCall(String id, String type, OpenAiFunctionCall function) {}
    public record OpenAiFunctionCall(String name, String arguments) {}
}
