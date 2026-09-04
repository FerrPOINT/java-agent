package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotEmpty;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record OpenAiChatRequest(
    // model is OPTIONAL: when omitted, the configured advertised model is used
    // (Hermes parity: body.get('model', self._model_name)).
    String model,
    String provider,
    @NotEmpty(message = "messages must contain at least one message") List<OpenAiMessage> messages,
    List<OpenAiTool> tools,
    Double temperature,
    @JsonProperty("model_options") @JsonAlias("modelOptions") Map<String, Object> modelOptions,
    @JsonProperty("max_tokens") @JsonAlias("maxTokens") Integer maxTokens,
    @JsonProperty("tool_choice") @JsonAlias("toolChoice") Object toolChoice,
    Object stream
) {
    public OpenAiChatRequest(String model,
                             List<OpenAiMessage> messages,
                             List<OpenAiTool> tools,
                             Double temperature,
                             Map<String, Object> modelOptions,
                             Integer maxTokens,
                             Boolean stream) {
        this(model, null, messages, tools, temperature, modelOptions, maxTokens, null, stream);
    }

    public record OpenAiMessage(
        String role,
        Object content,
        @JsonProperty("tool_calls") @JsonAlias("toolCalls") List<OpenAiToolCall> toolCalls,
        @JsonProperty("tool_call_id") @JsonAlias("toolCallId") String toolCallId
    ) {}

    public record OpenAiTool(String type, OpenAiFunction function) {}
    public record OpenAiFunction(String name, String description, Map<String, Object> parameters) {}
    public record OpenAiToolCall(String id, String type, OpenAiFunctionCall function) {}
    public record OpenAiFunctionCall(String name, String arguments) {}
}
