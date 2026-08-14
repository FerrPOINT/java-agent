package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * A {@link ModelClient} backed by a specific fallback configuration.
 * <p>
 * Created on-the-fly by {@link DefaultAgentRuntime} when a fallback is activated.
 * Each instance wraps a fresh {@code ChatModel} built from the fallback config's
 * provider, model, baseUrl, and apiKey — mirroring how Hermes
 * {@code resolve_provider_client()} constructs a new client for each
 * fallback chain entry.
 * <p>
 * The mapping and parameter-building logic reuses {@link LangChain4jModelClient}
 * internally — this class only swaps the underlying {@code ChatModel} with
 * one built from the fallback config.
 */
@Slf4j
public class FallbackModelClient implements ModelClient {

    private final ChatModel chatModel;
    private final String modelName;
    private final String provider;

    public FallbackModelClient(String provider, String model, String baseUrl, String apiKey,
                                int timeoutSeconds, int maxRetries, double temperature) {
        this.provider = provider;
        this.modelName = model;

        this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
            .maxRetries(maxRetries)
            .temperature(temperature)
            .build();
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools,
                                  ModelRequestOptions options) {
        // Delegate to a lightweight inner client that shares the same mapping logic
        List<dev.langchain4j.data.message.ChatMessage> chatMessages = messages.stream()
            .map(LangChain4jMessageMapper::toLangChain)
            .collect(java.util.stream.Collectors.toList());

        List<dev.langchain4j.agent.tool.ToolSpecification> specs = tools != null
            ? tools.stream().map(LangChain4jMessageMapper::toToolSpec).collect(java.util.stream.Collectors.toList())
            : List.of();

        dev.langchain4j.model.chat.request.ChatRequest request = dev.langchain4j.model.chat.request.ChatRequest.builder()
            .messages(chatMessages)
            .parameters(dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder()
                .modelName(modelName)
                .toolSpecifications(specs)
                .maxCompletionTokens(4096)
                .build())
            .build();

        try {
            dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
            dev.langchain4j.data.message.AiMessage aiMessage = response.aiMessage();

            if (aiMessage.hasToolExecutionRequests()) {
                List<com.azhukov.agent.core.model.ToolCall> calls = aiMessage.toolExecutionRequests().stream()
                    .map(r -> new com.azhukov.agent.core.model.ToolCall(r.id(), r.name(), r.arguments()))
                    .collect(java.util.stream.Collectors.toList());
                // Preserve text alongside tool calls — the text is "commentary"
                String text = aiMessage.text();
                if (text != null && !text.isBlank()) {
                    return ChatResponse.textAndToolCalls(text, calls);
                }
                return ChatResponse.toolCalls(calls);
            }

            return ChatResponse.text(aiMessage.text() != null ? aiMessage.text() : "");
        } catch (Exception e) {
            log.warn("FallbackModelClient complete() failed for {}/{}: {}", provider, modelName, e.getMessage());
            throw e;
        }
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Factory method: create a FallbackModelClient from a FallbackConfig and AgentProperties.
     */
    public static FallbackModelClient from(com.azhukov.agent.config.FallbackConfig config,
                                            AgentProperties properties) {
        return new FallbackModelClient(
            config.getProvider(),
            config.getModel(),
            config.getBaseUrl(),
            config.getApiKey(),
            properties.getModel().getTimeoutSeconds(),
            properties.getModel().getMaxRetries(),
            properties.getModel().getTemperature()
        );
    }
}