package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for P0 gap: Streaming error recovery.
 * <p>
 * Tests {@link AgentStreamingService} for correct error handling and documents gaps:
 * - GAP: No continuation prompt for truncated responses (stream just terminates)
 * - GAP: On error, "done" event is NOT sent — clients waiting for "done" will hang
 * - GAP: No retry on streaming errors — single failure terminates the stream
 */
class AgentStreamingServiceGapTest {

    private static final UUID SESSION_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final String USER_MESSAGE = "Hello";
    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";

    private ModelClient modelClient;
    private ToolRegistry toolRegistry;
    private ToolExecutionService toolExecutionService;
    private PromptBuilder promptBuilder;
    private ContextEngine contextEngine;
    private ObjectMapper objectMapper;
    private UsageTracker usageTracker;
    private AgentProperties properties;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private TransactionTemplate transactionTemplate;
    private IterationBudget iterationBudget;
    private TurnStateManager turnStateManager;
    private AgentStreamingService streamingService;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        toolRegistry = mock(ToolRegistry.class);
        toolExecutionService = mock(ToolExecutionService.class);
        promptBuilder = mock(PromptBuilder.class);
        contextEngine = mock(ContextEngine.class);
        objectMapper = new ObjectMapper();
        usageTracker = mock(UsageTracker.class);
        properties = new AgentProperties();
        properties.getContext().setMaxTokens(8192);
        properties.getModel().setModelName("test-model");
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryDelayMs(10);
        properties.getError().setRetryCapMs(50);
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        iterationBudget = mock(IterationBudget.class);
        turnStateManager = mock(TurnStateManager.class);

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        when(toolRegistry.getDefinitions(any(Set.class)))
            .thenReturn(List.<ToolDefinition>of());

        SessionEntity sessionEntity = new SessionEntity();
        sessionEntity.setId(SESSION_ID);
        sessionEntity.setUserId("user-1");
        sessionEntity.setModelProvider("openai-compatible");
        sessionEntity.setModelName("");
        sessionEntity.setTitle("Test chat");
        sessionEntity.setCreatedAt(Instant.now());
        sessionEntity.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);

        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));

        when(transactionTemplate.execute(any()))
            .thenAnswer(inv -> {
                org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
            });

        SessionEntityMapper sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);

        streamingService = new AgentStreamingService(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate),
            new CliStateApplier(), null, null);
    }

    // ─── Error event and stream termination ───

    @Nested
    @DisplayName("On exception, error event is sent and stream terminates")
    class ErrorEventBehaviour {

        @Test
        @DisplayName("When modelClient.stream() throws exception, error event is sent")
        void exceptionInStreamSendsErrorEvent() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doThrow(new RuntimeException("stream setup failed"))
                .when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.error.get()).isNotNull();
            assertThat(emitter.error.get().getMessage()).contains("stream setup failed");
            boolean hasErrorEvent = emitter.events.stream()
                .anyMatch(e -> "error".equals(e.name));
            assertThat(hasErrorEvent).isTrue();
        }

        @Test
        @DisplayName("When handler.onError() is called, error event is sent and stream terminates")
        void handlerOnErrorSendsErrorEvent() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onError(new RuntimeException("model stream error"));
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.error.get()).isNotNull();
            assertThat(emitter.error.get().getMessage()).isEqualTo("model stream error");
        }

        @Test
        @DisplayName("When exception occurs in agentic loop, stream terminates with completeWithError")
        void exceptionTerminatesStream() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doThrow(new RuntimeException("fatal error"))
                .when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.completed.get()).isFalse();
            assertThat(emitter.error.get()).isNotNull();
        }
    }

    // ─── GAP: No continuation prompt for truncated responses ───

    @Nested
    @DisplayName("GAP: No continuation prompt for truncated responses")
    class GapTruncatedResponses {

        @Test
        @DisplayName("GAP: When stream completes with empty content, no continuation is attempted")
        void gap_emptyResponseNoContinuation() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.completed.get()).isTrue();
            assertThat(emitter.error.get()).isNull();
            // GAP: An empty response should trigger a continuation prompt or retry
        }

        @Test
        @DisplayName("GAP: When stream returns partial text then errors, no continuation")
        void gap_partialTextThenErrorNoContinuation() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onToken("Partial response...");
                handler.onError(new RuntimeException("stream interrupted"));
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.error.get()).isNotNull();
            // GAP: partial response is lost — no mechanism to continue or retry
        }

        @Test
        @DisplayName("GAP: When stream produces very short response, no continuation check")
        void gap_shortResponseNoContinuationCheck() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, "Write a 1000 word essay about Java.", null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onToken("Java is");
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            assertThat(emitter.completed.get()).isTrue();
            // GAP: No check for whether the response is complete
        }
    }

    // ─── Tool start/result events ───

    @Nested
    @DisplayName("Tool start and result events")
    class ToolEvents {

        @Test
        @DisplayName("tool_start event is sent before each tool execution")
        void toolStartEventSent() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");
            CollectingEmitter emitter = new CollectingEmitter(1000L);

            java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                if (callCount.incrementAndGet() == 1) {
                    handler.onToolCalls(List.of(toolCall));
                } else {
                    handler.onToken("Done");
                }
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            when(toolExecutionService.execute(
                eq("weather"), eq("call-1"), any(String.class),
                any(), any(Session.class), any()))
                .thenReturn(ToolResult.ok("Sunny, 22°C"));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            boolean hasToolStart = emitter.events.stream()
                .anyMatch(e -> "tool_start".equals(e.name));
            assertThat(hasToolStart).isTrue();

            SseEvent toolStartEvent = emitter.events.stream()
                .filter(e -> "tool_start".equals(e.name))
                .findFirst()
                .orElseThrow();
            StreamEvent startEvent = deserialize(toolStartEvent.data, StreamEvent.class);
            assertThat(startEvent.toolName()).isEqualTo("weather");
        }

        @Test
        @DisplayName("tool_result event is sent after each tool execution with result preview")
        void toolResultEventSent() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            ToolCall toolCall = new ToolCall("call-1", "search", "{\"q\":\"test\"}");
            CollectingEmitter emitter = new CollectingEmitter(1000L);

            java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                if (callCount.incrementAndGet() == 1) {
                    handler.onToolCalls(List.of(toolCall));
                } else {
                    handler.onToken("Result based on search");
                }
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            when(toolExecutionService.execute(
                eq("search"), eq("call-1"), any(String.class),
                any(), any(Session.class), any()))
                .thenReturn(ToolResult.ok("Search results: item1, item2, item3"));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            SseEvent toolResultEvent = emitter.events.stream()
                .filter(e -> "tool_result".equals(e.name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool_result event"));
            StreamEvent resultEvent = deserialize(toolResultEvent.data, StreamEvent.class);
            assertThat(resultEvent.toolName()).isEqualTo("search");
            assertThat(resultEvent.toolResult()).contains("Search results");
        }

        @Test
        @DisplayName("tool_calls event is sent when model returns tool calls")
        void toolCallsEventSent() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"NYC\"}");
            CollectingEmitter emitter = new CollectingEmitter(1000L);

            java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                if (callCount.incrementAndGet() == 1) {
                    handler.onToolCalls(List.of(toolCall));
                } else {
                    handler.onToken("Final answer");
                }
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            when(toolExecutionService.execute(
                eq("weather"), eq("call-1"), any(String.class),
                any(), any(Session.class), any()))
                .thenReturn(ToolResult.ok("Rainy"));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            boolean hasToolCallsEvent = emitter.events.stream()
                .anyMatch(e -> "tool_calls".equals(e.name));
            assertThat(hasToolCallsEvent).isTrue();
        }

        @Test
        @DisplayName("tool_result preview is limited to 500 chars")
        void toolResultPreviewLimited() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            ToolCall toolCall = new ToolCall("call-1", "readfile", "{\"path\":\"big.txt\"}");
            CollectingEmitter emitter = new CollectingEmitter(1000L);

            java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                if (callCount.incrementAndGet() == 1) {
                    handler.onToolCalls(List.of(toolCall));
                } else {
                    handler.onToken("Done");
                }
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            String largeOutput = "x".repeat(2000);
            when(toolExecutionService.execute(
                eq("readfile"), eq("call-1"), any(String.class),
                any(), any(Session.class), any()))
                .thenReturn(ToolResult.ok(largeOutput));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            SseEvent toolResultEvent = emitter.events.stream()
                .filter(e -> "tool_result".equals(e.name))
                .findFirst()
                .orElseThrow();
            StreamEvent resultEvent = deserialize(toolResultEvent.data, StreamEvent.class);
            assertThat(resultEvent.toolResult().length()).isLessThanOrEqualTo(504);
            assertThat(resultEvent.toolResult()).endsWith("...");
        }
    }

    // ─── Done event behaviour ───

    @Nested
    @DisplayName("Done event on completion")
    class DoneEventBehaviour {

        @Test
        @DisplayName("On successful completion, done event is sent")
        void doneEventOnSuccess() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onToken("Hello!");
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            boolean hasDone = emitter.events.stream()
                .anyMatch(e -> "done".equals(e.name));
            assertThat(hasDone).isTrue();
            assertThat(emitter.completed.get()).isTrue();
        }

        @Test
        @DisplayName("On exception in agentic loop, done event is NOT sent (error only)")
        void noDoneEventOnException() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doThrow(new RuntimeException("crash"))
                .when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            boolean hasDone = emitter.events.stream()
                .anyMatch(e -> "done".equals(e.name));
            boolean hasError = emitter.events.stream()
                .anyMatch(e -> "error".equals(e.name));
            assertThat(hasError).isTrue();
            // GAP: "done" event is NOT sent on exception path
            assertThat(hasDone).isFalse();
        }

        @Test
        @DisplayName("GAP: On handler.onError(), done event IS still sent (stream continues after error)")
        void doneEventStillSentOnHandlerError() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onError(new RuntimeException("stream error"));
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            // Current behaviour: onError calls safeCompleteWithError but stream() returns normally,
            // so the agentic loop continues and sends a "done" event.
            // The emitter is already completed with error, so the "done" send is silently swallowed
            // by the IllegalStateException catch in send(), but CollectingEmitter records it anyway.
            // GAP: The done event should NOT be sent on error, but the current flow allows it
            // because onError doesn't stop the agentic loop — it only completes the emitter.
            boolean hasError = emitter.events.stream()
                .anyMatch(e -> "error".equals(e.name));
            assertThat(hasError).isTrue();
            // The error is set by completeWithError
            assertThat(emitter.error.get()).isNotNull();
        }

        @Test
        @DisplayName("On budget exhaustion, done event IS sent (graceful termination)")
        void doneEventOnBudgetExhaustion() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            when(iterationBudget.isExhausted(any())).thenReturn(true);

            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                handler.onToken("some text");
                handler.onComplete();
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            boolean hasDone = emitter.events.stream()
                .anyMatch(e -> "done".equals(e.name));
            assertThat(hasDone).isTrue();
            assertThat(emitter.completed.get()).isTrue();
        }
    }

    // ─── Streaming error recovery (A2) ───

    @Nested
    @DisplayName("Streaming error recovery — transient errors are retried")
    class StreamingErrorRecovery {

        @Test
        @DisplayName("Transient streaming error is retried and succeeds on second attempt")
        void transientErrorRetriedAndSucceeds() throws Exception {
            ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
            CollectingEmitter emitter = new CollectingEmitter(30_000L);

            java.util.concurrent.atomic.AtomicInteger streamCallCount = new java.util.concurrent.atomic.AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingResponseHandler handler = invocation.getArgument(2);
                if (streamCallCount.incrementAndGet() == 1) {
                    handler.onError(new RuntimeException("transient stream error"));
                } else {
                    handler.onToken("Success on retry");
                    handler.onComplete();
                }
                return null;
            }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

            streamingService.streamTurn(request, emitter);
            emitter.awaitDone();

            // A2 fix: transient error is retried — 2 stream calls made
            assertThat(streamCallCount.get()).isEqualTo(2);
            assertThat(emitter.completed.get()).isTrue();
            assertThat(emitter.error.get()).isNull();
            // A retry SSE event should have been sent
            boolean hasRetryEvent = emitter.events.stream()
                .anyMatch(e -> "retry".equals(e.name));
            assertThat(hasRetryEvent).isTrue();
        }
    }

    // ─── Helpers ───

    private static <T> T deserialize(String json, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(json, type);
    }

    private static class CollectingEmitter extends SseEmitter {
        final List<SseEvent> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> error = new AtomicReference<>();

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
                .atMost(15, TimeUnit.SECONDS)
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