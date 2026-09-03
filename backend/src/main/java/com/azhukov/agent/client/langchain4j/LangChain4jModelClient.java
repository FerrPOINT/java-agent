package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.client.credential.CredentialPool;
import com.azhukov.agent.client.credential.PooledCredential;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.TurnInterruptedException;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.service.ImageShrinkerService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
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
    private final ImageShrinkerService imageShrinker;
    private final String defaultBaseUrl;
    private final String defaultApiKey;

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this(properties, usageConsumer, errorClassifier, rateLimitTracker, null, null);
    }

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool) {
        this(properties, usageConsumer, errorClassifier, rateLimitTracker, credentialPool, null);
    }

    public LangChain4jModelClient(AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool, ImageShrinkerService imageShrinker) {
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
        this.credentialPool = credentialPool;
        this.imageShrinker = imageShrinker;

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
        this.defaultBaseUrl = baseUrl;
        this.defaultApiKey = apiKey;

        this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .maxRetries(0) // H21: Let DefaultAgentRuntime handle retries — avoids double-retry.
            .returnThinking(properties.getModel().isReturnThinking())
            .sendThinking(properties.getModel().isReturnThinking(),
                          properties.getModel().getThinkingFieldName())
            .build();
        this.streamingChatModel = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(properties.getModel().getModelName())
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .returnThinking(properties.getModel().isReturnThinking())
            .sendThinking(properties.getModel().isReturnThinking(),
                          properties.getModel().getThinkingFieldName())
            .build();
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        this(chatModel, streamingChatModel, properties, usageConsumer, errorClassifier, rateLimitTracker, null, null);
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool) {
        this(chatModel, streamingChatModel, properties, usageConsumer, errorClassifier, rateLimitTracker, credentialPool, null);
    }

    public LangChain4jModelClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                                   AgentProperties properties, java.util.function.Consumer<Usage> usageConsumer,
                                   ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                   CredentialPool credentialPool, ImageShrinkerService imageShrinker) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.properties = properties;
        this.usageConsumer = usageConsumer;
        this.errorClassifier = errorClassifier;
        this.rateLimitTracker = rateLimitTracker;
        this.credentialPool = credentialPool;
        this.imageShrinker = imageShrinker;
        this.defaultBaseUrl = properties != null && properties.getModel() != null
            ? properties.getModel().getBaseUrl()
            : "";
        this.defaultApiKey = properties != null && properties.getModel() != null
            ? properties.getModel().getApiKey()
            : "";
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
            .parameters(buildParameters(specs, reasoningEffort, fastMode, maxTokens, options))
            .build();

        log.debug("Sending {} messages to model {}", chatMessages.size(), request);

        if (rateLimitTracker != null && rateLimitTracker.shouldBackoff()) {
            log.warn("Rate limit backoff recommended — remaining={}, resetTime={}",
                rateLimitTracker.getRemaining(), rateLimitTracker.getResetTime());
        }

        try {
            dev.langchain4j.model.chat.response.ChatResponse response = chatModelFor(options).chat(request);
            AiMessage aiMessage = response.aiMessage();
            persistUsage(response, options);

            if (aiMessage.hasToolExecutionRequests()) {
                List<ToolCall> calls = new java.util.ArrayList<>();
                int callIdx = 0;
                for (var r : aiMessage.toolExecutionRequests()) {
                    String id = r.id();
                    if (id == null || id.isBlank()) {
                        id = ToolCall.deterministicCallId(r.name(), r.arguments(), callIdx);
                    }
                    calls.add(new ToolCall(id, r.name(), r.arguments()));
                    callIdx++;
                }
                // Preserve text alongside tool calls — the text is "commentary"
                // (interim assistant message) shown to the user before tool execution.
                // Mirrors Hermes _emit_interim_assistant_message().
                String text = aiMessage.text();
                if (text != null && !text.isBlank()) {
                    return new ChatResponse(text, calls, finishReasonOf(response));
                }
                return new ChatResponse("", calls, finishReasonOf(response));
            }

            // c2: carry the provider finish reason so BOTH runtimes can run the
            // shared recovery policies (LENGTH continuation). Missing → "STOP".
            return ChatResponse.text(aiMessage.text() != null ? aiMessage.text() : "", finishReasonOf(response));
        } catch (Exception e) {
            ErrorClassifier.ErrorType errorType = errorClassifier != null ? errorClassifier.classify(e) : ErrorClassifier.ErrorType.RETRYABLE;
            log.warn("Model complete() failed — errorType={}: {}", errorType, e.getMessage());
            throw e;
        }
    }

    /** Extract the langchain4j finish reason name, "STOP" when absent. */
    private static String finishReasonOf(dev.langchain4j.model.chat.response.ChatResponse response) {
        try {
            var fr = response.finishReason();
            return fr != null ? fr.name() : "STOP";
        } catch (Exception e) {
            return "STOP";
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
            .parameters(buildParameters(specs, reasoningEffort, fastMode, maxTokens, options))
            .build();

        // OpenAiStreamingChatModel.doChat() is async — block until streaming completes
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean(false);
        // H19: Guard flags declared at outer scope so both the handler callbacks and
        // the post-await fallback path can use them to prevent double onError()/onComplete().
        final java.util.concurrent.atomic.AtomicBoolean errored = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

        streamingChatModelFor(options).doChat(request, new StreamingChatResponseHandler() {
            private final StringBuilder content = new StringBuilder();

            @Override
            public void onPartialResponse(String partialResponse) {
                // If the turn was cancelled, throw to stop the stream early.
                // This will cause the onError callback to fire, releasing the latch.
                if (InterruptToken.isCancelledGlobally()) {
                    interrupted.set(true);
                    throw new TurnInterruptedException();
                }
                content.append(partialResponse);
                handler.onToken(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                try {
                    if (!completed.compareAndSet(false, true)) {
                        // H19: already completed (or error already reported) — skip duplicate.
                        return;
                    }
                    // H20: Persist token usage from the complete streaming response,
                    // mirroring what complete() does for the non-streaming path.
                    persistUsage(completeResponse, options);
                    if (completeResponse.aiMessage().hasToolExecutionRequests()) {
                        List<ToolCall> calls = new java.util.ArrayList<>();
                        int callIdx = 0;
                        for (var r : completeResponse.aiMessage().toolExecutionRequests()) {
                            String id = r.id();
                            if (id == null || id.isBlank()) {
                                id = ToolCall.deterministicCallId(r.name(), r.arguments(), callIdx);
                            }
                            calls.add(new ToolCall(id, r.name(), r.arguments()));
                            callIdx++;
                        }
                        handler.onToolCalls(calls);
                    } else if (content.isEmpty() && completeResponse.aiMessage().text() != null) {
                        handler.onToken(completeResponse.aiMessage().text());
                    }
                    // Propagate finish_reason to the handler for routing decisions
                    // (LENGTH → continuation, CONTENT_FILTER → error, STOP → normal)
                    String finishReason = null;
                    try {
                        if (completeResponse.finishReason() != null) {
                            finishReason = completeResponse.finishReason().name();
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract finishReason: {}", e.getMessage());
                    }
                    Long outputTokens = null;
                    try {
                        if (completeResponse.tokenUsage() != null
                            && completeResponse.tokenUsage().outputTokenCount() != null) {
                            outputTokens = completeResponse.tokenUsage().outputTokenCount().longValue();
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract outputTokens: {}", e.getMessage());
                    }
                    handler.onComplete(finishReason, outputTokens);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    // H19: Guard against double-invocation — if onCompleteResponse already
                    // completed successfully, or onError already fired, skip the second call.
                    if (!errored.compareAndSet(false, true)) {
                        return;
                    }
                    if (interrupted.get() || error instanceof TurnInterruptedException) {
                        log.info("Model stream interrupted by user cancellation");
                        // Mark as completed so the post-await path doesn't double-report.
                        completed.set(true);
                        handler.onComplete();
                    } else {
                        ErrorClassifier.ErrorType errorType = errorClassifier != null
                            ? errorClassifier.classify(error instanceof Exception e ? e : new RuntimeException(error))
                            : ErrorClassifier.ErrorType.RETRYABLE;
                        log.warn("Model stream() error — errorType={}: {}", errorType, error.getMessage());
                        errorRef.set(error);
                        handler.onError(error);
                    }
                } finally {
                    latch.countDown();
                }
            }
        });

        // Block until streaming completes or errors
        try {
            boolean done = latch.await(properties.getModel().getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!done && errorRef.get() == null) {
                // Latch timed out without completing or erroring — set timeout error
                String timeoutMsg = "Model stream() timed out after " + properties.getModel().getTimeoutSeconds() + "s";
                log.warn(timeoutMsg);
                errorRef.set(new java.util.concurrent.TimeoutException(timeoutMsg));
                // H19: Only call handler.onError if nothing has been reported yet.
                if (errored.compareAndSet(false, true)) {
                    handler.onError(errorRef.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Model stream() interrupted");
            errorRef.set(e);
            // H19: Only call handler.onError if nothing has been reported yet.
            if (errored.compareAndSet(false, true)) {
                handler.onError(e);
            }
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
        // P2-15: shrink image if it exceeds provider payload limits
        String effectiveBase64 = base64Image;
        if (imageShrinker != null) {
            effectiveBase64 = imageShrinker.shrinkIfNeeded(base64Image);
        }
        UserMessage message = UserMessage.from(
            dev.langchain4j.data.message.TextContent.from(prompt),
            dev.langchain4j.data.message.ImageContent.from(effectiveBase64, "image/png")
        );
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(message))
            .parameters(buildParameters(List.of(), resolveReasoningEffort(options), resolveFastMode(options),
                resolveMaxTokens(options), options))
            .build();
        dev.langchain4j.model.chat.response.ChatResponse response = chatModelFor(options).chat(request);
        persistUsage(response, options);
        return response.aiMessage().text();
    }

    private dev.langchain4j.model.chat.request.ChatRequestParameters buildParameters(List<ToolSpecification> specs,
                                                                                       int reasoningEffort,
                                                                                       boolean fastMode,
                                                                                       int maxTokens) {
        return buildParameters(specs, reasoningEffort, fastMode, maxTokens, null);
    }

    /**
     * Per-request model override: when options carry a modelName (from /model
     * or the API model field), it replaces the configured default in the
     * request parameters. The OpenAI-compatible client sends the override to
     * the provider on every call that uses these parameters.
     */
    private dev.langchain4j.model.chat.request.ChatRequestParameters buildParameters(List<ToolSpecification> specs,
                                                                                       int reasoningEffort,
                                                                                       boolean fastMode,
                                                                                       int maxTokens,
                                                                                       ModelRequestOptions options) {
        String effectiveModel = effectiveModelName(options);
        var builder = OpenAiChatRequestParameters.builder()
            .modelName(effectiveModel)
            .toolSpecifications(specs);
        Double temperature = temperatureForModel(effectiveModel, properties.getModel().getTemperature());
        if (temperature != null) {
            builder.temperature(temperature);
        }
        int effectiveMaxTokens = fastMode ? Math.min(maxTokens, 2048) : maxTokens;
        if (requiresMaxCompletionTokens(effectiveModel, effectiveBaseUrl(options))) {
            builder.maxCompletionTokens(effectiveMaxTokens);
        } else {
            builder.maxOutputTokens(effectiveMaxTokens);
        }
        String serviceTier = clean(options != null ? options.serviceTier() : null);
        if (serviceTier != null) {
            builder.serviceTier(serviceTier);
        }
        if (reasoningEffort > 0
            && !fastMode
            && shouldSendTopLevelReasoningEffort(effectiveModel, effectiveBaseUrl(options))) {
            builder.reasoningEffort(effortToString(reasoningEffort));
        }
        return builder.build();
    }

    private ChatModel chatModelFor(ModelRequestOptions options) {
        if (options == null || !options.hasTransportOverride()) {
            return chatModel;
        }
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(effectiveBaseUrl(options))
            .apiKey(effectiveApiKey(options))
            .modelName(effectiveModelName(options))
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .maxRetries(0)
            .returnThinking(properties.getModel().isReturnThinking())
            .sendThinking(properties.getModel().isReturnThinking(),
                properties.getModel().getThinkingFieldName())
            .build();
    }

    private StreamingChatModel streamingChatModelFor(ModelRequestOptions options) {
        if (options == null || !options.hasTransportOverride()) {
            return streamingChatModel;
        }
        return dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder()
            .baseUrl(effectiveBaseUrl(options))
            .apiKey(effectiveApiKey(options))
            .modelName(effectiveModelName(options))
            .timeout(java.time.Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .returnThinking(properties.getModel().isReturnThinking())
            .sendThinking(properties.getModel().isReturnThinking(),
                properties.getModel().getThinkingFieldName())
            .build();
    }

    private String effectiveModelName(ModelRequestOptions options) {
        if (options != null && options.modelName() != null && !options.modelName().isBlank()) {
            return options.modelName();
        }
        return properties.getModel().getModelName();
    }

    private String effectiveBaseUrl(ModelRequestOptions options) {
        if (options != null && options.baseUrl() != null && !options.baseUrl().isBlank()) {
            return options.baseUrl();
        }
        return defaultBaseUrl;
    }

    private String effectiveApiKey(ModelRequestOptions options) {
        if (options != null && options.apiKey() != null && !options.apiKey().isBlank()) {
            return options.apiKey();
        }
        return defaultApiKey;
    }

    static boolean requiresMaxCompletionTokens(String model, String baseUrl) {
        String host = baseUrlHostname(baseUrl);
        return "api.openai.com".equals(host)
            || hostMatches(host, "openai.azure.com")
            || "api.githubcopilot.com".equals(host)
            || host.endsWith(".githubcopilot.com")
            || modelForcesMaxCompletionTokens(model);
    }

    static boolean shouldSendTopLevelReasoningEffort(String model, String baseUrl) {
        if (isKimiModel(model) || isDeepSeekReasoningModel(model) || isGlmReasoningModel(model)) {
            return true;
        }
        String host = baseUrlHostname(baseUrl);
        return ("api.openai.com".equals(host) || hostMatches(host, "openai.azure.com"))
            && isOpenAiReasoningModel(model);
    }

    static boolean modelForcesMaxCompletionTokens(String model) {
        String m = clean(model);
        if (m == null) {
            return false;
        }
        m = m.toLowerCase(java.util.Locale.ROOT);
        int slash = m.lastIndexOf('/');
        if (slash >= 0) {
            m = m.substring(slash + 1);
        }
        return m.startsWith("gpt-4o")
            || m.startsWith("gpt-4.1")
            || m.startsWith("gpt-5")
            || m.startsWith("o1")
            || m.startsWith("o3")
            || m.startsWith("o4");
    }

    private static boolean isOpenAiReasoningModel(String model) {
        String bare = bareModelName(model);
        return bare != null
            && (bare.startsWith("gpt-5")
                || bare.startsWith("o1")
                || bare.startsWith("o3")
                || bare.startsWith("o4"));
    }

    private static boolean isDeepSeekReasoningModel(String model) {
        String bare = bareModelName(model);
        return bare != null
            && (bare.startsWith("deepseek-reasoner")
                || bare.startsWith("deepseek-r1"));
    }

    private static boolean isGlmReasoningModel(String model) {
        String m = clean(model);
        if (m == null) {
            return false;
        }
        m = m.toLowerCase(java.util.Locale.ROOT);
        return m.contains("glm-5.2")
            || m.contains("glm-5-2")
            || m.contains("glm-5p2")
            || m.contains("glm-5.3")
            || m.contains("glm-5-3")
            || m.contains("glm-5p3");
    }

    static Double temperatureForModel(String model, double configuredTemperature) {
        if (isKimiModel(model)) {
            return null;
        }
        if (isArceeTrinityThinking(model)) {
            return 0.5d;
        }
        return configuredTemperature;
    }

    private static boolean isKimiModel(String model) {
        String bare = bareModelName(model);
        return bare != null && (bare.startsWith("kimi-") || "kimi".equals(bare));
    }

    private static boolean isArceeTrinityThinking(String model) {
        return "trinity-large-thinking".equals(bareModelName(model));
    }

    private static String bareModelName(String model) {
        String m = clean(model);
        if (m == null) {
            return null;
        }
        m = m.toLowerCase(java.util.Locale.ROOT);
        int slash = m.lastIndexOf('/');
        return slash >= 0 ? m.substring(slash + 1) : m;
    }

    private static String baseUrlHostname(String baseUrl) {
        String raw = clean(baseUrl);
        if (raw == null) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(raw.contains("://") ? raw : "//" + raw);
            String host = uri.getHost();
            return host != null
                ? host.toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.+$", "")
                : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean hostMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Map numeric reasoning effort (0-100) to the WIRE effort set.
     * Hermes parity (transports/chat_completions.py): never forward
     * xhigh/max/ultra verbatim — providers clamp to their own levels
     * (Gemini: low/medium/high; sending 'max' made LiteLLM raise
     * "Invalid reasoning effort" and kill the whole turn). The wider
     * Hermes effort vocabulary is a UI concept; the wire only sees
     * low/medium/high.
     */
    private String effortToString(int effort) {
        if (effort <= 0) return "none";
        if (effort <= 40) return "low";
        if (effort <= 70) return "medium";
        return "high";
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
            // Hermes' wider vocabulary maps onto the same scale; the WIRE set
            // stays low/medium/high (see effortToString) — max/ultra never
            // reach the provider verbatim.
            case "xhigh", "max", "ultra" -> 100;
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
                            // Hermes parity (_canonicalize_api_tool_calls, conversation_loop.py:1230):
                            // canonical wire form for historical tool-call arguments on EVERY
                            // send — separators (",", ":") + sorted keys, memoized per unique string.
                            // Stabilizes provider prompt-cache prefixes across iterations.
                            .arguments(canonicalizeArguments(c.arguments()))
                            .build())
                        .collect(Collectors.toList());
                    String content = message.content();
                    yield content != null && !content.isBlank()
                        ? AiMessage.from(content, requests)
                        : AiMessage.from(requests);
                }
                yield AiMessage.from(message.content() != null ? message.content() : "");
            }
            case TOOL -> dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                message.toolCallId(), null, message.content() != null ? message.content() : "");
        };
    }

    // ── Send-path tool-call argument canonicalization (Hermes parity) ──────

    /** Value-keyed memo: pure deterministic function of the input string. */
    private static final com.fasterxml.jackson.databind.ObjectMapper CANON_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.Map<String, String> CANON_ARGS_CACHE =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                return size() > 4096;
            }
        });

    /**
     * Canonical wire form of a tool-call arguments JSON string: compact separators,
     * sorted keys (Hermes json.dumps(separators=(",", ":"), sort_keys=True)).
     * Returns the input unchanged when it doesn't parse — the persisted history
     * must never be mangled by this pass; repair owns malformed strings.
     */
    static String canonicalizeArguments(String argStr) {
        if (argStr == null || argStr.isEmpty()) {
            return argStr;
        }
        String cached = CANON_ARGS_CACHE.get(argStr);
        if (cached != null) {
            return cached;
        }
        String canonical;
        try {
            var tree = CANON_MAPPER.readTree(argStr);
            var out = new java.util.TreeMap<String, Object>();
            tree.fields().forEachRemaining(e -> out.put(e.getKey(), unwrap(e.getValue())));
            canonical = CANON_MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return argStr; // unparseable — pass through untouched (repair owns those)
        }
        CANON_ARGS_CACHE.put(argStr, canonical);
        return canonical;
    }

    private static Object unwrap(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var m = new java.util.TreeMap<String, Object>();
            node.fields().forEachRemaining(e -> m.put(e.getKey(), unwrap(e.getValue())));
            return m;
        }
        if (node.isArray()) {
            var l = new java.util.ArrayList<Object>();
            node.forEach(v -> l.add(unwrap(v)));
            return l;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? node.longValue() : node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        return node.asText();
    }

    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        return ToolSpecification.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(LangChain4jToolSchemaMapper.toJsonObjectSchema(definition.parameters()))
            .build();
    }

    private void persistUsage(dev.langchain4j.model.chat.response.ChatResponse response, ModelRequestOptions options) {
        try {
            if (response.tokenUsage() == null || usageConsumer == null) return;
            int prompt = response.tokenUsage().inputTokenCount();
            int completion = response.tokenUsage().outputTokenCount();
            // Build TokenUsage with cache token tracking (real token counts from API response)
            TokenUsage usage = TokenUsage.of(prompt, completion);
            usageConsumer.accept(new Usage(effectiveProvider(options), effectiveModelName(options), prompt, completion));
        } catch (Exception e) {
            log.warn("Could not persist model usage: {}", e.getMessage());
        }
    }

    private String effectiveProvider(ModelRequestOptions options) {
        if (options != null && options.provider() != null && !options.provider().isBlank()) {
            return options.provider();
        }
        return properties.getModel().getProvider();
    }

    public record Usage(String provider, String model, int promptTokens, int completionTokens) {
        public int totalTokens() { return promptTokens + completionTokens; }
    }
}
