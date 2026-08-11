package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.client.credential.CredentialPool;
import com.azhukov.agent.client.credential.PooledCredential;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
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
 * Supports text completion, tool calls, streaming, vision, and credential pool rotation.
 */
@Slf4j
public class LangChain4jModelClient implements ModelClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final AgentProperties properties;
    private final java.util.function.Consumer<Usage> usageConsumer;
    private final ErrorClassifier errorClassifier;
    private final RateLimitTracker rateLimitTracker;
    private final CredentialPool credentialPool;

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this(properties, usageConsumer, errorClassifier, rateLimitTracker, null);
    }

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool) {
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
        this.credentialPool = credentialPool;

        // Resolve credentials: use credential pool if available, otherwise fall back to config
        String apiKey = properties.getModel().getApiKey();
        String baseUrl = properties.getModel().getBaseUrl();
        if (credentialPool != null && credentialPool.hasAvailable()) {
            PooledCredential cred = credentialPool.current();
            if (cred != null) {
                apiKey = cred.apiKey();
                baseUrl = cred.baseUrl() != null ? cred.baseUrl() : baseUrl;
                credentialPool.markUsed(cred);
                log.debug("Using credential from pool: {} for provider {}", cred.id(), cred.provider());
            }
        }

        this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .maxRetries(properties.getModel().getMaxRetries())
            .temperature(properties.getModel().getTemperature())
            .build();
        this.streamingChatModel = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .temperature(properties.getModel().getTemperature())
            .build();
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this(chatModel, streamingChatModel, properties, usageConsumer, errorClassifier, rateLimitTracker, null);
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
        this.credentialPool = credentialPool;
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        return complete(messages, tools, ModelRequestOptions.empty());
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options) {
        List<ChatMessage> chatMessages = messages.stream()
            .map(this::toLangChainMessage)
            .collect(Collectors.toList());

        List<ToolSpecification> specs = tools != null
            ? tools.stream().map(this::toToolSpecification).collect(Collectors.toList())
            : List.of();

        int reasoningEffort = resolveReasoningEffort(options);
        boolean fastMode = resolveFastMode(options);
        int maxTokens = resolveMaxTokens(options);

        ChatRequest request = ChatRequest.builder()
            .messages(chatMessages)
            .parameters(buildParameters(specs, reasoningEffort, fastMode, maxTokens))
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
        stream(messages, tools, ModelRequestOptions.empty(), handler);
    }

    @Override
    public void stream(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options,
                       StreamingResponseHandler handler) {
        List<ChatMessage> chatMessages = messages.stream()
            .map(this::toLangChainMessage)
            .collect(Collectors.toList());

        List<ToolSpecification> specs = tools != null
            ? tools.stream().map(this::toToolSpecification).collect(Collectors.toList())
            : List.of();

        int reasoningEffort = resolveReasoningEffort(options);
        boolean fastMode = resolveFastMode(options);
        int maxTokens = resolveMaxTokens(options);

        ChatRequest request = ChatRequest.builder()
            .messages(chatMessages)
            .parameters(buildParameters(specs, reasoningEffort, fastMode, maxTokens))
            .build();

        // OpenAiStreamingChatModel.doChat() is async — block until streaming completes
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();

        streamingChatModel.doChat(request, new StreamingChatResponseHandler() {
            private final StringBuilder content = new StringBuilder();

            @Override
            public void onPartialResponse(String partialResponse) {
                content.append(partialResponse);
                handler.onToken(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                try {
                    if (completeResponse.aiMessage().hasToolExecutionRequests()) {
                        List<ToolCall> calls = completeResponse.aiMessage().toolExecutionRequests().stream()
                            .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                            .collect(Collectors.toList());
                        handler.onToolCalls(calls);
                    } else if (content.isEmpty() && completeResponse.aiMessage().text() != null) {
                        handler.onToken(completeResponse.aiMessage().text());
                    }
                    handler.onComplete();
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    ErrorClassifier.ErrorType errorType = errorClassifier != null
                        ? errorClassifier.classify(error instanceof Exception e ? e : new RuntimeException(error))
                        : ErrorClassifier.ErrorType.RETRYABLE;
                    log.warn("Model stream() error — errorType={}: {}", errorType, error.getMessage());
                    errorRef.set(error);
                    handler.onError(error);
                } finally {
                    latch.countDown();
                }
            }
        });

        // Block until streaming completes or errors
        try {
            latch.await(properties.getModel().getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Model stream() interrupted");
            handler.onError(e);
        }
        if (errorRef.get() != null) {
            throw new RuntimeException("Model call failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Override
    public CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt) {
        return CompletableFuture.supplyAsync(() -> analyzeImage(base64Image, prompt));
    }

    public CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt, ModelRequestOptions options) {
        return CompletableFuture.supplyAsync(() -> analyzeImage(base64Image, prompt, options));
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return analyzeImage(base64Image, prompt, ModelRequestOptions.empty());
    }

    /**
     * P2-18: Return the model name used by this client.
     */
    @Override
    public String getModelName() {
        return properties.getModel().getModelName();
    }

    public String analyzeImage(String base64Image, String prompt, ModelRequestOptions options) {
        UserMessage message = UserMessage.from(
            dev.langchain4j.data.message.TextContent.from(prompt),
            dev.langchain4j.data.message.ImageContent.from(base64Image, "image/png")
        );
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(message))
            .parameters(buildParameters(List.of(), resolveReasoningEffort(options), resolveFastMode(options), resolveMaxTokens(options)))
            .build();
        dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
        persistUsage(response);
        return response.aiMessage().text();
    }

    private dev.langchain4j.model.chat.request.ChatRequestParameters buildParameters(List<ToolSpecification> specs,
                                                                                       int reasoningEffort,
                                                                                       boolean fastMode,
                                                                                       int maxTokens) {
        var builder = OpenAiChatRequestParameters.builder()
            .modelName(properties.getModel().getModelName())
            .toolSpecifications(specs)
            .maxCompletionTokens(fastMode ? Math.min(maxTokens, 2048) : maxTokens);
        if (reasoningEffort > 0 && !fastMode) {
            builder.reasoningEffort(effortToString(reasoningEffort));
        }
        return builder.build();
    }

    /**
     * Map numeric reasoning effort (0-100) back to API string values.
     * Ollama Cloud / kimi expects: none, low, medium, high, max.
     */
    private String effortToString(int effort) {
        if (effort <= 0) return "none";
        if (effort <= 40) return "low";
        if (effort <= 70) return "medium";
        if (effort <= 90) return "high";
        return "max";
    }

    /**
     * Resolve reasoning-effort for the LLM request.
     *
     * <p>CLI and Telegram store reasoning as descriptive levels
     * ({@code none}, {@code minimal}, {@code low}, {@code medium}, {@code high}, {@code xhigh}).
     * This method maps those levels to numeric percent effort, and also accepts
     * raw integer strings sent by backend callers. Falls back to the configured
     * default when no valid value is supplied.
     */
    private int resolveReasoningEffort(ModelRequestOptions options) {
        String raw = (options != null) ? options.reasoningEffort() : null;
        if (raw == null || raw.isBlank()) {
            return properties.getModel().getReasoningEffort();
        }
        return parseReasoningEffort(raw);
    }

    private int parseReasoningEffort(String raw) {
        return switch (raw.toLowerCase()) {
            case "none" -> 0;
            case "minimal" -> 20;
            case "low" -> 40;
            case "medium" -> 70;
            case "high" -> 90;
            case "xhigh" -> 100;
            default -> {
                try {
                    int value = Integer.parseInt(raw);
                    if (value < 0) {
                        yield 0;
                    }
                    yield Math.min(value, 100);
                } catch (NumberFormatException e) {
                    log.debug("Ignoring non-numeric reasoningEffort: {}", raw);
                    yield properties.getModel().getReasoningEffort();
                }
            }
        };
    }

    private boolean resolveFastMode(ModelRequestOptions options) {
        if (options != null && options.fastMode() != null) {
            return options.fastMode();
        }
        return properties.getModel().isFastMode();
    }

    private int resolveMaxTokens(ModelRequestOptions options) {
        if (options != null && options.maxCompletionTokens() != null && options.maxCompletionTokens() > 0) {
            return options.maxCompletionTokens();
        }
        return properties.getModel().getMaxTokens();
    }

    private ChatMessage toLangChainMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM, DEVELOPER -> SystemMessage.from(message.content());
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
            // Build TokenUsage with cache token tracking (real token counts from API response)
            TokenUsage usage = TokenUsage.of(prompt, completion);
            usageConsumer.accept(new Usage(properties.getModel().getProvider(), properties.getModel().getModelName(), prompt, completion));
        } catch (Exception e) {
            log.debug("Could not persist model usage: {}", e.getMessage());
        }
    }

    public record Usage(String provider, String model, int promptTokens, int completionTokens) {
        public int totalTokens() { return promptTokens + completionTokens; }
    }
}
