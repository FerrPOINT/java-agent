package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
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

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/v1/chat/completions")
@RequiredArgsConstructor
public class ChatCompletionsController {

    private final AgentRuntime agentRuntime;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final OpenAiMapper openAiMapper;

    @PostMapping
    public Object completions(@Valid @RequestBody OpenAiChatRequest request) {
        if (Boolean.TRUE.equals(request.stream())) {
            return streamCompletions(request);
        }
        return syncCompletion(request);
    }

    private OpenAiChatResponse syncCompletion(OpenAiChatRequest request) {
        Session session = Session.create("openai-user", "openai-compatible", request.model());
        List<Message> messages = buildMessages(session, request);
        List<ToolDefinition> tools = buildTools(request);
        ChatResponse response = agentRuntime.run(messages, tools, requestOptions(request));
        return openAiMapper.toOpenAiResponse(request.model(), response);
    }

    private SseEmitter streamCompletions(OpenAiChatRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        String model = request.model();

        CompletableFuture.runAsync(() -> {
            try {
                Session session = Session.create("openai-user", "openai-compatible", model);
                List<Message> messages = buildMessages(session, request);
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
                        sendSse(emitter, createFinishEvent(id, model));
                        sendDone(emitter);
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        // OpenAI-compatible streaming errors are delivered as a
                        // normal error envelope, followed by the terminal marker
                        // so SDK consumers never wait for another chunk.
                        sendSse(emitter, createErrorEvent(error.getMessage()));
                        sendDone(emitter);
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                sendSse(emitter, createErrorEvent(e.getMessage()));
                sendDone(emitter);
                emitter.complete();
            }
        });

        return emitter;
    }

    private ModelRequestOptions requestOptions(OpenAiChatRequest request) {
        return new ModelRequestOptions(request.model(), null, null, null, null, null, request.maxTokens());
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
            emitter.completeWithError(e);
        }
    }

    private void sendDone(SseEmitter emitter) {
        try {
            // This produces the literal SSE payload data:[DONE] (not a JSON
            // string), which is the OpenAI terminal sentinel.
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException e) {
            emitter.completeWithError(e);
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
                null))
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
                "stop"))
        );
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
