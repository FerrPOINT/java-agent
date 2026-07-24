package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentStreamingService {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamingService.class);

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    public AgentStreamingService(ModelClient modelClient,
                                 ToolRegistry toolRegistry,
                                 PromptBuilder promptBuilder,
                                 ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
    }

    public SseEmitter streamTurn(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(request.timeoutMs() != null ? request.timeoutMs() : 600_000L);

        CompletableFuture.runAsync(() -> {
            try {
                List<Message> messages = List.of(
                    promptBuilder.buildSystemMessage(null),
                    Message.user(request.message())
                );
                List<ToolDefinition> tools = toolRegistry.getDefinitions();

                modelClient.stream(messages, tools, new StreamingResponseHandler() {
                    @Override
                    public void onToken(String token) {
                        send(emitter, new StreamEvent("token", token, null, null));
                    }

                    @Override
                    public void onToolCalls(List<ToolCall> toolCalls) {
                        send(emitter, new StreamEvent("tool_calls", null, toolCalls, null));
                    }

                    @Override
                    public void onComplete() {
                        send(emitter, new StreamEvent("done", null, null, null));
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Streaming error", error);
                        send(emitter, new StreamEvent("error", null, null, error.getMessage()));
                        emitter.completeWithError(error);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to start streaming", e);
                send(emitter, new StreamEvent("error", null, null, e.getMessage()));
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(event.type())
                .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
