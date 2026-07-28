package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * LangChain4j-backed OpenAI-compatible model client.
 * Supports text completion, tool calls, streaming, and vision.
 */
@Slf4j
public class LangChain4jModelClient implements ModelClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final AgentProperties properties;
    private final java.util.function.Consumer<Usage> usageConsumer;
    private final ErrorClassifier errorClassifier;
    private final RateLimitTracker rateLimitTracker;

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
        this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(properties.getModel().getBaseUrl())
            .apiKey(properties.getModel().getApiKey())
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .maxRetries(properties.getModel().getMaxRetries())
            .temperature(properties.getModel().getTemperature())
            .build();
        this.streamingChatModel = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
            .baseUrl(properties.getModel().getBaseUrl())
            .apiKey(properties.getModel().getApiKey())
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .temperature(properties.getModel().getTemperature())
            .build();
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        List<ChatMessage> chatMessages = messages.stream()
            .map(this::toLangChainMessage)
            .collect(Collectors.toList());

        List<ToolSpecification> specs = tools != null
            ? tools.stream().map(this::toToolSpecification).collect(Collectors.toList())
            : List.of();

        ChatRequest request = ChatRequest.builder()
            .messages(chatMessages)
            .toolSpecifications(specs)
            .build();

        log.debug("Sending {} messages to model {}", chatMessages.size(), request);

        if (rateLimitTracker != null && rateLimitTracker.shouldBackoff()) {
            log.warn("Rate limit backoff recommended — remaining={}, resetTime={}",
                rateLimitTracker.getRemaining(), rateLimitTracker.getResetTime());
        }

        try {
            dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
            AiMessage aiMessage = response.aiMessage();
            persistUsage(response);

            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolCall> calls = aiMessage.toolExecutionRequests().stream()
                    .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                    .collect(Collectors.toList());
                return ChatResponse.toolCalls(calls);
            }

            return ChatResponse.text(aiMessage.text());
        } catch (Exception e) {
            ErrorClassifier.ErrorType errorType = errorClassifier != null ? errorClassifier.classify(e) : ErrorClassifier.ErrorType.RETRYABLE;
            log.warn("Model complete() failed — errorType={}: {}", errorType, e.getMessage());
            throw e;
        }
    }

    @Override
    public void stream(List<Message> messages, List<ToolDefinition> tools, StreamingResponseHandler handler) {
        List<ChatMessage> chatMessages = messages.stream()
            .map(this::toLangChainMessage)
            .collect(Collectors.toList());

        List<ToolSpecification> specs = tools != null
            ? tools.stream().map(this::toToolSpecification).collect(Collectors.toList())
            : List.of();

        ChatRequest request = ChatRequest.builder()
            .messages(chatMessages)
            .toolSpecifications(specs)
            .build();

        streamingChatModel.doChat(request, new StreamingChatResponseHandler() {
            private final StringBuilder content = new StringBuilder();

            @Override
            public void onPartialResponse(String partialResponse) {
                content.append(partialResponse);
                handler.onToken(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                if (completeResponse.aiMessage().hasToolExecutionRequests()) {
                    List<ToolCall> calls = completeResponse.aiMessage().toolExecutionRequests().stream()
                        .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                        .collect(Collectors.toList());
                    handler.onToolCalls(calls);
                } else if (content.isEmpty() && completeResponse.aiMessage().text() != null) {
                    handler.onToken(completeResponse.aiMessage().text());
                }
                handler.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                ErrorClassifier.ErrorType errorType = errorClassifier != null
                    ? errorClassifier.classify(error instanceof Exception e ? e : new RuntimeException(error))
                    : ErrorClassifier.ErrorType.RETRYABLE;
                log.warn("Model stream() error — errorType={}: {}", errorType, error.getMessage());
                handler.onError(error);
            }
        });
    }

    @Override
    public CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt) {
        return CompletableFuture.supplyAsync(() -> analyzeImage(base64Image, prompt));
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        UserMessage message = UserMessage.from(
            dev.langchain4j.data.message.TextContent.from(prompt),
            dev.langchain4j.data.message.ImageContent.from(base64Image, "image/png")
        );
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(message))
            .build();
        dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
        persistUsage(response);
        return response.aiMessage().text();
    }

    private ChatMessage toLangChainMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    var requests = message.toolCalls().stream()
                        .map(c -> ToolExecutionRequest.builder()
                            .id(c.id())
                            .name(c.name())
                            .arguments(c.arguments())
                            .build())
                        .collect(Collectors.toList());
                    yield AiMessage.from(requests);
                }
                yield AiMessage.from(message.content());
            }
            case TOOL -> dev.langchain4j.data.message.ToolExecutionResultMessage.from(message.toolCallId(), null, message.content());
        };
    }

    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        return ToolSpecification.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(toJsonSchema(definition.parameters()))
            .build();
    }

    private JsonObjectSchema toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return JsonObjectSchema.builder().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                builder.addStringProperty(e.getKey(), descriptionOf(e.getValue()));
            }
        }
        if (required != null) {
            builder.required(required);
        }
        return builder.build();
    }

    private String descriptionOf(Object spec) {
        if (spec instanceof Map m) {
            Object desc = m.get("description");
            return desc != null ? desc.toString() : "";
        }
        return "";
    }

    private void persistUsage(dev.langchain4j.model.chat.response.ChatResponse response) {
        try {
            if (response.tokenUsage() == null || usageConsumer == null) return;
            int prompt = response.tokenUsage().inputTokenCount();
            int completion = response.tokenUsage().outputTokenCount();
            usageConsumer.accept(new Usage(properties.getModel().getProvider(), properties.getModel().getModelName(), prompt, completion));
        } catch (Exception e) {
            log.debug("Could not persist model usage: {}", e.getMessage());
        }
    }

    public record Usage(String provider, String model, int promptTokens, int completionTokens) {
        public int totalTokens() { return promptTokens + completionTokens; }
    }
}
