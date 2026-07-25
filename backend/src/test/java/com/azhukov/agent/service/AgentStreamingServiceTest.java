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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentStreamingServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String USER_MESSAGE = "Hello";
    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";

    private ModelClient modelClient;
    private ToolRegistry toolRegistry;
    private PromptBuilder promptBuilder;
    private ObjectMapper objectMapper;
    private AgentStreamingService streamingService;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        toolRegistry = mock(ToolRegistry.class);
        promptBuilder = mock(PromptBuilder.class);
        objectMapper = new ObjectMapper();

        when(promptBuilder.buildSystemMessage(null)).thenReturn(Message.system(SYSTEM_PROMPT));
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("weather", "Get weather", Map.of())
        ));

        streamingService = new AgentStreamingService(modelClient, toolRegistry, promptBuilder, objectMapper);
    }

    @Test
    void streamTurnEmitsTokenEvents() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("Hello");
            handler.onToken(" world");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        SseEmitter returned = streamingService.streamTurn(request, emitter);
        assertThat(returned).isSameAs(emitter);

        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.events).hasSize(3);
        assertThat(emitter.events.get(0).name).isEqualTo("token");
        assertThat(deserialize(emitter.events.get(0).data, StreamEvent.class).token()).isEqualTo("Hello");
        assertThat(emitter.events.get(1).name).isEqualTo("token");
        assertThat(deserialize(emitter.events.get(1).data, StreamEvent.class).token()).isEqualTo(" world");
        assertThat(emitter.events.get(2).name).isEqualTo("done");
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnEmitsToolCallsEvent() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToolCalls(List.of(toolCall));
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        List<SseEvent> events = emitter.events;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).name).isEqualTo("tool_calls");
        StreamEvent streamEvent = deserialize(events.get(0).data, StreamEvent.class);
        assertThat(streamEvent.toolCalls()).hasSize(1);
        assertThat(streamEvent.toolCalls().get(0).name()).isEqualTo("weather");
        assertThat(events.get(1).name).isEqualTo("done");
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnEmitsDoneEventAndCompletesEmitter() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("Done");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.events).hasSize(2);
        assertThat(emitter.events.get(0).name).isEqualTo("token");
        assertThat(emitter.events.get(1).name).isEqualTo("done");
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnEmitsErrorOnModelClientException() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
        RuntimeException failure = new RuntimeException("model exploded");

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onError(failure);
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0).name).isEqualTo("error");
        StreamEvent streamEvent = deserialize(emitter.events.get(0).data, StreamEvent.class);
        assertThat(streamEvent.error()).isEqualTo("model exploded");
        assertThat(emitter.error.get()).isNotNull().hasMessage("model exploded");
    }

    @Test
    void streamTurnUsesDefaultTimeoutWhenRequestTimeoutMsIsNull() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, null);

        AtomicReference<Long> capturedTimeout = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedTimeout.set(getEmitterTimeout(streamingService.streamTurn(request)));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request);

        await().pollInterval(50, TimeUnit.MILLISECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> capturedTimeout.get() != null);
        assertThat(capturedTimeout.get()).isEqualTo(600_000L);
    }

    @Test
    void streamTurnUsesProvidedTimeoutWhenRequestTimeoutMsIsSet() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 42L);

        AtomicReference<Long> capturedTimeout = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedTimeout.set(getEmitterTimeout(streamingService.streamTurn(request)));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request);

        await().pollInterval(50, TimeUnit.MILLISECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> capturedTimeout.get() != null);
        assertThat(capturedTimeout.get()).isEqualTo(42L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static <T> T deserialize(String json, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(json, type);
    }

    private static long getEmitterTimeout(SseEmitter emitter) throws Exception {
        Field timeoutField = emitter.getClass().getSuperclass().getDeclaredField("timeout");
        timeoutField.setAccessible(true);
        return ((Long) timeoutField.get(emitter)).longValue();
    }

    /**
     * Custom SseEmitter subclass that captures every sent event and completion/error.
     */
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
            this.completed.set(true);
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            this.error.set(ex);
            super.completeWithError(ex);
        }

        void awaitDone() {
            await().pollInterval(50, TimeUnit.MILLISECONDS)
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> completed.get() || error.get() != null);
        }
    }

    private static class SseEvent {
        final String id;
        final String name;
        final String data;

        SseEvent(SseEmitter.SseEventBuilder builder) {
            try {
                Set<?> dataWithMediaTypes = builder.build();
                StringBuilder payload = new StringBuilder();
                for (Object dwmt : dataWithMediaTypes) {
                    Field dataField = getField(dwmt.getClass(), "data");
                    Object dataValue = dataField.get(dwmt);
                    if (dataValue != null) {
                        payload.append(dataValue.toString());
                    }
                }
                String rendered = payload.toString();
                this.name = parseEventName(rendered);
                this.data = extractData(rendered);
                this.id = null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static String parseEventName(String rendered) {
            if (rendered == null) return null;
            int eventIdx = rendered.indexOf("event:");
            if (eventIdx >= 0) {
                int nl = rendered.indexOf('\n', eventIdx);
                return rendered.substring(eventIdx + 6, nl >= 0 ? nl : rendered.length()).trim();
            }
            return null;
        }

        private static String extractData(String rendered) {
            if (rendered == null) return null;
            int dataIdx = rendered.lastIndexOf("data:");
            if (dataIdx >= 0) {
                return rendered.substring(dataIdx + 5).trim();
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
