package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.api.dto.OpenAiStreamChunk;
import com.azhukov.agent.api.dto.OpenAiStreamError;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.HistorySanitizer;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.ResponseEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/v1/chat/completions")
@RequiredArgsConstructor
@Slf4j
public class ChatCompletionsController {

    private final AgentRuntime agentRuntime;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final AgentProperties properties;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final OpenAiMapper openAiMapper;
    private final com.azhukov.agent.service.OpenAiSessionService openAiSessionService;
    private final com.azhukov.agent.core.tool.ToolExecutionService toolExecutionService;
    private final ExecutorService streamingExecutor =
        java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("openai-sse-", 0).factory());

    /**
     * Hermes parity: an omitted model defaults to the configured advertised
     * model instead of failing validation.
     */
    private String effectiveModel(OpenAiChatRequest request) {
        if (request.model() != null && !request.model().isBlank()) {
            return request.model().trim();
        }
        return OpenAiModelRouting.advertisedModel(properties);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        streamingExecutor.shutdown();
        try {
            if (!streamingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                streamingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @PostMapping
    public ResponseEntity<Object> completions(
            @Valid @RequestBody OpenAiChatRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                value = com.azhukov.agent.service.OpenAiSessionService.SESSION_ID_HEADER,
                required = false) String sessionIdHeader,
            @org.springframework.web.bind.annotation.RequestHeader(
                value = com.azhukov.agent.service.OpenAiSessionService.SESSION_KEY_HEADER,
                required = false) String sessionKeyHeader) {
        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok(streamCompletions(request, sessionIdHeader, sessionKeyHeader));
        }
        return syncCompletion(request, sessionIdHeader, sessionKeyHeader);
    }

    private ResponseEntity<Object> syncCompletion(OpenAiChatRequest request,
                                                    String sessionIdHeader,
                                                    String sessionKeyHeader) {
        String model = effectiveModel(request);
        var sessionContext = openAiSessionService.resolveChatCompletions(
            sessionIdHeader, sessionKeyHeader, model, null);
        Session session = sessionContext.session() != null
            ? sessionContext.session()
            : Session.create("openai-user", "openai-compatible", model);
        List<Message> messages = buildMessages(session, request);
        // Session continuity (Hermes parity): prior turns from the SAME
        // continued session are replayed before this request's messages.
        messages.addAll(messages.size() - countRequestMessages(request),
            openAiSessionService.historyFor(sessionContext));
        List<ToolDefinition> tools = buildTools(request);
        ChatResponse response = runWithToolLoop(session, messages, tools, requestOptions(request));
        openAiSessionService.persistTurn(sessionContext,
            request.messages().stream().map(openAiMapper::toMessage).toList(), response);
        var body = openAiMapper.toOpenAiResponse(model, response);
        return ResponseEntity.ok()
            .header(com.azhukov.agent.service.OpenAiSessionService.SESSION_ID_HEADER,
                sessionContext.responseSessionId())
            .body(body);
    }

    /** Max model→tool round-trips per request (Hermes agent loop bound). */
    private static final int MAX_TOOL_ITERATIONS = 8;

    /**
     * Hermes parity: /v1/chat/completions runs a server-side agent loop —
     * when the model emits tool calls, the server executes them (via
     * {@code ToolExecutionService}), appends results, and calls the model
     * again until a final textual answer or the iteration bound.
     */
    private ChatResponse runWithToolLoop(Session session, List<Message> messages,
                                         List<ToolDefinition> tools,
                                         ModelRequestOptions options) {
        ChatResponse response = agentRuntime.run(messages, tools, options);
        for (int i = 0; i < MAX_TOOL_ITERATIONS
                && response != null && response.hasToolCalls(); i++) {
            Message assistantWithCalls = com.azhukov.agent.core.model.Message
                .assistantWithToolCalls(response.content(), response.toolCalls(), 1);
            messages.add(assistantWithCalls);
            for (com.azhukov.agent.core.model.ToolCall call : response.toolCalls()) {
                com.azhukov.agent.core.model.ToolResult result = toolExecutionService.execute(
                    call.name(), call.pairingId(), call.arguments(), assistantWithCalls, session);
                messages.add(com.azhukov.agent.core.model.Message.toolResult(
                    call.pairingId(), result.content(), 1));
            }
            response = agentRuntime.run(messages, tools, options);
        }
        return response;
    }

    private int countRequestMessages(OpenAiChatRequest request) {
        return request.messages() != null ? request.messages().size() : 0;
    }

    private SseEmitter streamCompletions(OpenAiChatRequest request,
                                         String sessionIdHeader,
                                         String sessionKeyHeader) {
        SseEmitter emitter = new SseEmitter(600_000L);
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        String model = effectiveModel(request);
        var sessionContext = openAiSessionService.resolveChatCompletions(
            sessionIdHeader, sessionKeyHeader, model, null);

        // Session continuity header on the SSE channel (Hermes returns it on
        // the response headers of the stream too).
        try {
            // SSE comment line (ignored by OpenAI SDK parsers by spec) carrying
            // the session id for continuity-aware clients.
            emitter.send(SseEmitter.event()
                .comment("session-id: " + sessionContext.responseSessionId()));
        } catch (Exception ignored) {
            // continuity hint is best-effort; do not break the stream
        }

        CompletableFuture.runAsync(() -> {
            try {
                Session session = sessionContext.session() != null
                    ? sessionContext.session()
                    : Session.create("openai-user", "openai-compatible", model);
                List<Message> messages = buildMessages(session, request);
                messages.addAll(messages.size() - countRequestMessages(request),
                    openAiSessionService.historyFor(sessionContext));
                List<ToolDefinition> tools = buildTools(request);

                modelClient.stream(HistorySanitizer.sanitizeForModelRequest(messages), tools,
                    requestOptions(request), new StreamingResponseHandler() {
                    @Override
                    public void onToken(String token) {
                        sendSse(emitter, createDeltaEvent(id, model, token, null));
                    }

                    @Override
                    public void onToolCalls(List<ToolCall> toolCalls) {
                        sendSse(emitter, createDeltaEvent(id, model, null, toolCalls));
                    }

                    @Override
                    public void onComplete() {
                        sendSse(emitter, createFinishEvent(id, model, null, null));
                        sendDone(emitter);
                        safeComplete(emitter);
                    }

                    @Override
                    public void onComplete(String finishReason, Long outputTokens) {
                        // rev-121: real finish_reason + usage on the terminal
                        // chunk (Hermes api_server.py:5535-5548).
                        sendSse(emitter, createFinishEvent(id, model, finishReason, outputTokens));
                        sendDone(emitter);
                        safeComplete(emitter);
                    }

                    @Override
                    public void onError(Throwable error) {
                        // OpenAI-compatible streaming errors are delivered as a
                        // normal error envelope, followed by the terminal marker
                        // so SDK consumers never wait for another chunk.
                        sendSse(emitter, createErrorEvent(error.getMessage()));
                        sendDone(emitter);
                        safeComplete(emitter);
                    }
                });
            } catch (Exception e) {
                sendSse(emitter, createErrorEvent(e.getMessage()));
                sendDone(emitter);
                safeComplete(emitter);
            }
        }, streamingExecutor);

        return emitter;
    }

    private ModelRequestOptions requestOptions(OpenAiChatRequest request) {
        // Hermes api_server parity: the advertised alias ("hermes-agent") or a
        // configured model-route alias must resolve to the runtime target model
        // before hitting the provider — the alias itself is not a model name.
        String runtimeModel = OpenAiModelRouting.runtimeModelName(properties, effectiveModel(request));
        return new ModelRequestOptions(runtimeModel, null, null, null, null, null, request.maxTokens());
    }

    private List<Message> buildMessages(Session session, OpenAiChatRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(promptBuilder.buildSystemMessage(session));
        for (OpenAiChatRequest.OpenAiMessage m : request.messages()) {
            messages.add(openAiMapper.toMessage(m));
        }
        return messages;
    }

    private List<ToolDefinition> buildTools(OpenAiChatRequest request) {
        return request.tools() != null
            ? request.tools().stream().map(openAiMapper::toToolDefinition).toList()
            : toolRegistry.getDefinitions();
    }

    /**
     * This endpoint is OpenAI-compatible. The OpenAI streaming convention is
     * an unnamed data-only SSE sequence, terminated by {@code data: [DONE]}.
     * Giving every frame an {@code event: message} name and serialising a custom
     * error DTO made standard SDKs wait forever or fail to decode the stream.
     */
    private void sendSse(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            log.debug("OpenAI SSE send failed (client disconnected): {}", e.getMessage());
            safeComplete(emitter);
        } catch (IllegalStateException e) {
            log.debug("OpenAI SSE emitter already completed: {}", e.getMessage());
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            // This produces the literal SSE payload data:[DONE] (not a JSON
            // string), which is the OpenAI terminal sentinel.
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException e) {
            log.debug("OpenAI SSE done send failed (client disconnected): {}", e.getMessage());
        } catch (IllegalStateException e) {
            log.debug("OpenAI SSE emitter already completed: {}", e.getMessage());
        }
    }

    /** Complete the emitter, swallowing IllegalStateException on client-disconnect race. */
    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("OpenAI SSE emitter already completed: {}", e.getMessage());
        }
    }

    private OpenAiStreamChunk createDeltaEvent(String id, String model, String token, List<ToolCall> toolCalls) {
        return new OpenAiStreamChunk(
            id,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiStreamChunk.Choice(0,
                new OpenAiStreamChunk.Delta("assistant", token, toOpenAiToolCalls(toolCalls)),
                null)),
            null
        );
    }

    private OpenAiStreamChunk createFinishEvent(String id, String model) {
        return new OpenAiStreamChunk(
            id,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiStreamChunk.Choice(0,
                new OpenAiStreamChunk.Delta("assistant", null, null),
                "stop")),
            null
        );
    }

    /**
     * rev-121 Hermes parity (api_server.py:5535-5548): the finish chunk carries
     * usage (prompt/completion/total tokens) and the REAL finish reason —
     * "length" for truncation, "error" for failure, "stop" otherwise. Cost-
     * tracking clients read the terminal chunk's usage; a hard-coded
     * finish_reason="stop" masked truncations and errors as clean completions.
     */
    private OpenAiStreamChunk createFinishEvent(String id, String model, String finishReason, Long outputTokens) {
        String reason = finishReason != null ? mapFinishReason(finishReason) : "stop";
        return new OpenAiStreamChunk(
            id,
            "chat.completion.chunk",
            Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiStreamChunk.Choice(0,
                new OpenAiStreamChunk.Delta("assistant", null, null),
                reason)),
            new OpenAiStreamChunk.Usage(
                0,
                outputTokens != null ? outputTokens.intValue() : 0,
                outputTokens != null ? outputTokens.intValue() : 0)
        );
    }

    /** Map provider finish reasons to the OpenAI triad Hermes uses. */
    private static String mapFinishReason(String finishReason) {
        if (finishReason == null) return "stop";
        return switch (finishReason.toUpperCase()) {
            case "LENGTH", "MAX_TOKENS" -> "length";
            case "CONTENT_FILTER" -> "content_filter";
            case "ERROR" -> "error";
            default -> "stop";
        };
    }

    private OpenAiStreamError createErrorEvent(String message) {
        return new OpenAiStreamError("streaming_error", message);
    }

    private List<OpenAiStreamChunk.ToolCall> toOpenAiToolCalls(List<ToolCall> calls) {
        if (calls == null) return null;
        return calls.stream()
            .map(c -> new OpenAiStreamChunk.ToolCall(
                c.id() != null ? c.id() : UUID.randomUUID().toString(),
                "function",
                new OpenAiStreamChunk.Function(c.name(), c.arguments())
            ))
            .toList();
    }

}
