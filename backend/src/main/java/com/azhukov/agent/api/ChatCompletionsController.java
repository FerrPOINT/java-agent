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
        ChatResponse response = agentRuntime.run(messages, tools);
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

                modelClient.stream(messages, tools, new StreamingResponseHandler() {
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
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        sendSse(emitter, createErrorEvent(error.getMessage()));
                        emitter.completeWithError(error);
                    }
                });
            } catch (Exception e) {
                sendSse(emitter, createErrorEvent(e.getMessage()));
                emitter.completeWithError(e);
            }
        });

        return emitter;
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

    private void sendSse(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name("message")
                .data(objectMapper.writeValueAsString(data)));
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
