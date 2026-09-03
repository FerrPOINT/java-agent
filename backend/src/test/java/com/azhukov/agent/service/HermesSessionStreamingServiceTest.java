package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HermesSessionStreamingServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID EFFECTIVE_SESSION_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");

    @Mock
    private AgentRuntimeService agentRuntimeService;

    private ObjectMapper objectMapper;
    private HermesSessionStreamingService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new HermesSessionStreamingService(agentRuntimeService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void streamTurnEmitsHermesLifecycleEvents() throws Exception {
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(
                EFFECTIVE_SESSION_ID,
                "streamed answer",
                List.of("web_search"),
                true,
                false,
                "gpt-test",
                7,
                1000));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        service.streamTurn(
            ChatRequest.simple(SESSION_ID, "hello", null, 10_000L),
            SESSION_ID,
            Map.of("model_lock", "accepted"),
            emitter);

        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.events).extracting(event -> event.name)
            .containsExactly(
                "run.started",
                "message.started",
                "assistant.delta",
                "assistant.completed",
                "run.completed",
                "done");

        JsonNode started = objectMapper.readTree(emitter.events.get(0).data);
        assertThat(started.path("session_id").asText()).isEqualTo(SESSION_ID.toString());
        assertThat(started.path("run_id").asText()).startsWith("run_");
        assertThat(started.path("seq").asInt()).isEqualTo(1);
        assertThat(started.path("user_message").path("content").asText()).isEqualTo("hello");

        JsonNode delta = payload(emitter, "assistant.delta");
        assertThat(delta.path("session_id").asText()).isEqualTo(EFFECTIVE_SESSION_ID.toString());
        assertThat(delta.path("delta").asText()).isEqualTo("streamed answer");

        JsonNode completed = payload(emitter, "run.completed");
        assertThat(completed.path("session_id").asText()).isEqualTo(EFFECTIVE_SESSION_ID.toString());
        assertThat(completed.path("completed").asBoolean()).isTrue();
        assertThat(completed.path("usage").path("input_tokens").asInt()).isEqualTo(7);
        assertThat(completed.path("usage").path("total_tokens").asInt()).isEqualTo(7);
        assertThat(completed.path("runtime").path("model_lock").asText()).isEqualTo("accepted");
        assertThat(completed.path("runtime").path("model").asText()).isEqualTo("gpt-test");
        assertThat(completed.path("messages").get(0).path("role").asText()).isEqualTo("assistant");
        assertThat(completed.path("messages").get(0).path("content").asText()).isEqualTo("streamed answer");
        assertThat(completed.path("messages").get(0).path("tool_calls").get(0).asText()).isEqualTo("web_search");
    }

    @Test
    void runCompletedUsesAuthoritativeTurnTranscriptWhenAvailable() throws Exception {
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(
                EFFECTIVE_SESSION_ID,
                "final answer",
                List.of(),
                true,
                false,
                "gpt-test",
                7,
                1000,
                List.of(
                    Map.of(
                        "role", "assistant",
                        "content", "Let me search.",
                        "tool_calls", List.of(Map.of(
                            "id", "call_1",
                            "type", "function",
                            "function", Map.of(
                                "name", "web_search",
                                "arguments", "{}")))),
                    Map.of(
                        "role", "tool",
                        "content", "results",
                        "tool_call_id", "call_1",
                        "tool_name", "web_search"),
                    Map.of(
                        "role", "assistant",
                        "content", "final answer")
                )));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        service.streamTurn(
            ChatRequest.simple(SESSION_ID, "hello", null, 10_000L),
            SESSION_ID,
            Map.of(),
            emitter);

        emitter.awaitDone();

        JsonNode completed = payload(emitter, "run.completed");
        JsonNode messages = completed.path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("content").asText()).isEqualTo("Let me search.");
        assertThat(messages.get(0).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(messages.get(0).path("tool_calls").get(0).path("function").path("name").asText())
            .isEqualTo("web_search");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(1).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("final answer");
    }

    private JsonNode payload(CollectingEmitter emitter, String name) throws IOException {
        SseEvent event = emitter.events.stream()
            .filter(candidate -> name.equals(candidate.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing event " + name));
        return objectMapper.readTree(event.data);
    }

    private static class CollectingEmitter extends SseEmitter {
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        CollectingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(new SseEvent(builder));
        }

        @Override
        public void complete() {
            completed.set(true);
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            error.set(ex);
            super.completeWithError(ex);
        }

        void awaitDone() {
            await().pollInterval(50, TimeUnit.MILLISECONDS)
                .atMost(15, TimeUnit.SECONDS)
                .until(() -> completed.get() || error.get() != null);
        }
    }

    private static class SseEvent {
        final String name;
        final String data;

        SseEvent(SseEmitter.SseEventBuilder builder) {
            try {
                Set<?> dataWithMediaTypes = builder.build();
                StringBuilder payload = new StringBuilder();
                for (Object dataWithMediaType : dataWithMediaTypes) {
                    Field dataField = getField(dataWithMediaType.getClass(), "data");
                    Object dataValue = dataField.get(dataWithMediaType);
                    if (dataValue != null) {
                        payload.append(dataValue);
                    }
                }
                String rendered = payload.toString();
                this.name = parseEventName(rendered);
                this.data = extractData(rendered);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String parseEventName(String rendered) {
            int eventIndex = rendered.indexOf("event:");
            if (eventIndex < 0) {
                return null;
            }
            int newlineIndex = rendered.indexOf('\n', eventIndex);
            return rendered.substring(eventIndex + 6, newlineIndex >= 0 ? newlineIndex : rendered.length()).trim();
        }

        private static String extractData(String rendered) {
            int dataIndex = rendered.lastIndexOf("data:");
            if (dataIndex >= 0) {
                return rendered.substring(dataIndex + 5).trim();
            }
            return rendered;
        }

        private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }
    }
}
