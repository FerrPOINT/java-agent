package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.budget.IterationBudget;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that InterruptToken is properly wired into AgentStreamingService,
 * allowing mid-stream cancellation of agentic turns.
 */
class AgentStreamingInterruptTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
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
    private InterruptToken interruptToken;
    private SteerBuffer steerBuffer;
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
        interruptToken = new InterruptToken();
        steerBuffer = new SteerBuffer();

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        when(toolRegistry.getDefinitions(any(Set.class)))
            .thenReturn(List.of(new ToolDefinition("weather", "Get weather", Map.of())));

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
            new RuntimeConfigService(), interruptToken, steerBuffer,
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate),
            new CliStateApplier(), null, null, new com.azhukov.agent.core.metadata.ModelMetadataService());
    }

    /**
     * When interruptToken.cancel() is called before streaming starts,
     * the loop should immediately emit an "interrupted" event and complete.
     */
    @Test
    void interruptBeforeLoopIteration_abortsImmediately() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);

        // Pre-cancel the session
        interruptToken.cancel(SESSION_ID);

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("should not reach here");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();

        // Should have an "interrupted" event
        boolean hasInterrupted = emitter.events.stream()
            .anyMatch(e -> "interrupted".equals(e.name));
        assertThat(hasInterrupted).isTrue();

        // Should NOT have any token events (model was never called)
        boolean hasToken = emitter.events.stream()
            .anyMatch(e -> "token".equals(e.name));
        assertThat(hasToken).isFalse();
    }

    /**
     * When interruptToken.cancel() is called during streaming (between tokens),
     * the onToken handler should stop emitting further tokens.
     */
    @Test
    void interruptMidStream_stopsEmittingTokens() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("first");
            // Cancel after the first token
            interruptToken.cancel(SESSION_ID);
            handler.onToken("second");  // should be skipped due to interrupt check
            handler.onToken("third");   // should be skipped
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();

        // Only "first" should have been emitted as a token event
        var tokenEvents = emitter.events.stream()
            .filter(e -> "token".equals(e.name))
            .toList();
        assertThat(tokenEvents).hasSize(1);
        StreamEvent firstToken = deserialize(tokenEvents.get(0).data, StreamEvent.class);
        assertThat(firstToken.token()).isEqualTo("first");

        // Should have an "interrupted" event from the loop check after streaming returns
        boolean hasInterrupted = emitter.events.stream()
            .anyMatch(e -> "interrupted".equals(e.name));
        assertThat(hasInterrupted).isTrue();
    }

    /**
     * When interruptToken.cancel() is called before tool execution,
     * tools should not be executed and the stream should terminate with "interrupted".
     */
    @Test
    void interruptBeforeToolExecution_preventsToolCall() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");

        CollectingEmitter emitter = new CollectingEmitter(500L);

        // First model call returns tool calls; cancel interrupt before tools run
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToolCalls(List.of(toolCall));
            handler.onComplete();
            // Cancel right after the model returns tool calls
            interruptToken.cancel(SESSION_ID);
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();

        // Should have an "interrupted" event
        boolean hasInterrupted = emitter.events.stream()
            .anyMatch(e -> "interrupted".equals(e.name));
        assertThat(hasInterrupted).isTrue();

        // Should NOT have tool_start or tool_result events
        boolean hasToolStart = emitter.events.stream()
            .anyMatch(e -> "tool_start".equals(e.name));
        assertThat(hasToolStart).isFalse();
    }

    /**
     * After interruptToken.reset(), a new streaming turn should proceed normally.
     */
    @Test
    void interruptReset_allowsSubsequentTurn() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);

        // Cancel, then reset before the turn
        interruptToken.cancel(SESSION_ID);
        assertThat(interruptToken.isCancelled(SESSION_ID)).isTrue();
        interruptToken.reset(SESSION_ID);
        assertThat(interruptToken.isCancelled(SESSION_ID)).isFalse();

        CollectingEmitter emitter = new CollectingEmitter(500L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("hello");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();

        // Should have token events (model was called normally after reset)
        boolean hasToken = emitter.events.stream()
            .anyMatch(e -> "token".equals(e.name));
        assertThat(hasToken).isTrue();

        // Should NOT have "interrupted" event
        boolean hasInterrupted = emitter.events.stream()
            .anyMatch(e -> "interrupted".equals(e.name));
        assertThat(hasInterrupted).isFalse();
    }

    /**
     * Steer notes injected via steerBuffer should be appended to tool results
     * in the streaming path.
     */
    @Test
    void steerNote_isInjectedIntoToolResult() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, USER_MESSAGE, null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");

        // Add a steer note before the turn
        steerBuffer.steer(SESSION_ID, "Focus on temperature");

        CollectingEmitter emitter = new CollectingEmitter(500L);

        // First call returns tool calls, second returns text
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            if (firstCall.getAndSet(false)) {
                handler.onToolCalls(List.of(toolCall));
                handler.onComplete();
            } else {
                handler.onToken("done");
                handler.onComplete();
            }
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        when(toolExecutionService.execute(eq("weather"), eq("call-1"), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22°C"));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();

        // Verify tool was executed
        boolean hasToolResult = emitter.events.stream()
            .anyMatch(e -> "tool_result".equals(e.name));
        assertThat(hasToolResult).isTrue();

        // Steer buffer should be consumed
        assertThat(steerBuffer.hasPending(SESSION_ID)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static <T> T deserialize(String json, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(json, type);
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
                .atMost(5, TimeUnit.SECONDS)
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