package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class HermesSessionStreamingService {

    private static final long DEFAULT_TIMEOUT_MS = 600_000L;

    private final AgentRuntimeService agentRuntimeService;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("hermes-session-stream-", 0).factory());

    public SseEmitter streamTurn(ChatRequest request, UUID requestedSessionId, Map<String, Object> runtime) {
        long timeout = request != null && request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;
        return streamTurn(request, requestedSessionId, runtime, new SseEmitter(timeout));
    }

    public SseEmitter streamTurn(
            ChatRequest request,
            UUID requestedSessionId,
            Map<String, Object> runtime,
            Runnable onComplete) {
        long timeout = request != null && request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;
        return streamTurn(request, requestedSessionId, runtime, new SseEmitter(timeout), onComplete);
    }

    SseEmitter streamTurn(
            ChatRequest request,
            UUID requestedSessionId,
            Map<String, Object> runtime,
            SseEmitter emitter) {
        return streamTurn(request, requestedSessionId, runtime, emitter, null);
    }

    SseEmitter streamTurn(
            ChatRequest request,
            UUID requestedSessionId,
            Map<String, Object> runtime,
            SseEmitter emitter,
            Runnable onComplete) {
        try {
            executor.submit(() -> run(request, requestedSessionId, runtime, emitter, onComplete));
        } catch (RuntimeException e) {
            completeCallback(onComplete);
            throw e;
        }
        return emitter;
    }

    private void run(
            ChatRequest request,
            UUID requestedSessionId,
            Map<String, Object> runtime,
            SseEmitter emitter,
            Runnable onComplete) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        EventWriter events = new EventWriter(emitter, runId, requestedSessionId);
        Map<String, Object> requestedRuntime = runtime != null
            ? new LinkedHashMap<>(runtime)
            : new LinkedHashMap<>();
        try {
            events.emit("run.started", Map.of(
                "user_message", Map.of(
                    "role", "user",
                    "content", request != null && request.message() != null ? request.message() : ""),
                "runtime", requestedRuntime));
            events.emit("message.started", Map.of(
                "message", Map.of("id", messageId, "role", "assistant")));

            ChatResponseDto response = agentRuntimeService.runTurn(request);
            UUID effectiveSessionId = response.sessionId() != null ? response.sessionId() : requestedSessionId;
            String content = response.content() != null ? response.content() : "";
            Map<String, Object> effectiveRuntime = runtimeWithResponseModel(requestedRuntime, response);
            Map<String, Object> usage = usage(response);

            if (!response.completed()) {
                String message = !content.isBlank() ? content : "agent run did not complete";
                events.emit("error", eventMap(effectiveSessionId, "message", message));
                return;
            }

            if (!content.isEmpty()) {
                events.emit("assistant.delta", eventMap(
                    effectiveSessionId,
                    "message_id", messageId,
                    "delta", content));
            }

            List<Map<String, Object>> messages = responseMessages(response, content);
            events.emit("assistant.completed", eventMap(
                effectiveSessionId,
                "message_id", messageId,
                "content", content,
                "completed", true,
                "partial", false,
                "interrupted", false,
                "runtime", effectiveRuntime));
            events.emit("run.completed", eventMap(
                effectiveSessionId,
                "message_id", messageId,
                "completed", true,
                "messages", messages,
                "usage", usage,
                "runtime", effectiveRuntime));
        } catch (Exception e) {
            log.warn("Hermes session stream failed for session {}: {}", requestedSessionId, e.getMessage());
            events.emit("error", Map.of("message", errorMessage(e)));
        } finally {
            events.emit("done", Map.of());
            emitter.complete();
            completeCallback(onComplete);
        }
    }

    private static void completeCallback(Runnable onComplete) {
        if (onComplete != null) {
            try {
                onComplete.run();
            } catch (RuntimeException e) {
                log.debug("Hermes session stream completion callback failed: {}", e.getMessage());
            }
        }
    }

    private Map<String, Object> runtimeWithResponseModel(Map<String, Object> runtime, ChatResponseDto response) {
        Map<String, Object> effective = new LinkedHashMap<>(runtime);
        if (response.modelUsed() != null && !response.modelUsed().isBlank()) {
            effective.put("model", response.modelUsed());
        }
        return effective;
    }

    private Map<String, Object> usage(ChatResponseDto response) {
        int inputTokens = response.contextTokens() != null ? response.contextTokens() : 0;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        usage.put("total_tokens", inputTokens);
        return usage;
    }

    private Map<String, Object> assistantMessage(String content, List<String> toolCalls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }
        return message;
    }

    private List<Map<String, Object>> responseMessages(ChatResponseDto response, String content) {
        if (response.messages() != null && !response.messages().isEmpty()) {
            return response.messages();
        }
        return List.of(assistantMessage(content, response.toolCalls()));
    }

    private Map<String, Object> eventMap(UUID sessionId, Object... keyValues) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (sessionId != null) {
            payload.put("session_id", sessionId.toString());
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            payload.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return payload;
    }

    private String errorMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private final class EventWriter {
        private final SseEmitter emitter;
        private final String runId;
        private final UUID defaultSessionId;
        private int seq;

        private EventWriter(SseEmitter emitter, String runId, UUID defaultSessionId) {
            this.emitter = emitter;
            this.runId = runId;
            this.defaultSessionId = defaultSessionId;
        }

        private void emit(String name, Map<String, Object> fields) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (fields != null) {
                payload.putAll(fields);
            }
            if (defaultSessionId != null) {
                payload.putIfAbsent("session_id", defaultSessionId.toString());
            }
            payload.putIfAbsent("run_id", runId);
            payload.putIfAbsent("seq", ++seq);
            payload.putIfAbsent("ts", Instant.now().toEpochMilli() / 1000.0);
            try {
                emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(name)
                    .data(objectMapper.writeValueAsString(payload)));
            } catch (IllegalStateException e) {
                log.debug("Hermes session SSE event not sent (emitter completed): {}", e.getMessage());
            } catch (IOException e) {
                log.debug("Failed to send Hermes session SSE event: {}", e.getMessage());
            }
        }
    }
}
