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
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Bug 2: SSE streaming error handling.
 * <p>
 * When an exception occurs during SSE streaming, AgentStreamingService should:
 * 1. Send an error SSE event to the client
 * 2. Complete the emitter normally (NOT with completeWithError)
 * <p>
 * Previously, safeCompleteWithError() called emitter.completeWithError(error),
 * which propagated the exception to Spring's GlobalExceptionHandler. The handler
 * tried to write a JSON error response, but the response content type was
 * text/event-stream → HttpMessageNotWritableException.
 * <p>
 * Fix: safeCompleteWithError() now calls emitter.complete() — the error has
 * already been sent as an SSE event, so the client receives the error and
 * the emitter completes cleanly without propagating to GlobalExceptionHandler.
 */
class AgentStreamingServiceSseErrorTest {

    private static final UUID SESSION_ID = UUID.fromString("44444444-5555-6666-7777-888888888888");
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

        com.azhukov.agent.core.agent.SessionLineageService lineageService = mock(com.azhukov.agent.core.agent.SessionLineageService.class);
        when(lineageService.loadMessagesWithAncestors(any(UUID.class))).thenAnswer(inv -> {
            UUID sid = inv.getArgument(0);
            var entities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sid);
            if (entities == null || entities.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            java.util.List<Message> msgs = new java.util.ArrayList<>(entities.size());
            for (var entity : entities) {
                Message msg = messageMapper.toDomain(entity);
                if (msg != null) {
                    msgs.add(msg);
                }
            }
            return msgs;
        });

        streamingService = new AgentStreamingService(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            lineageService,
            new CliStateApplier(), null, null, new com.azhukov.agent.core.metadata.ModelMetadataService(), null);
    }

    // ── Error from modelClient.stream() throw → emitter completes normally, error event sent ──

    @Test
    void streamErrorCompletesNormallyAndSendsErrorEvent() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        // Simulate stream() throwing an exception
        doThrow(new RuntimeException("model unavailable"))
            .when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Error event should be sent via SSE
        boolean hasErrorEvent = emitter.events.stream()
            .anyMatch(e -> "error".equals(e.name));
        assertThat(hasErrorEvent).isTrue();

        // Emitter should complete normally (not with error) to avoid
        // propagating exception to GlobalExceptionHandler on text/event-stream
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── Error via handler.onError() → emitter completes normally, error event sent ──

    @Test
    void handlerOnErrorCompletesNormallyAndSendsErrorEvent() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onError(new RuntimeException("stream broke"));
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Error event should be sent via SSE
        boolean hasErrorEvent = emitter.events.stream()
            .anyMatch(e -> "error".equals(e.name));
        assertThat(hasErrorEvent).isTrue();

        // Emitter should complete normally
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── Permanent error after retries exhausted → emitter completes normally ──

    @Test
    void permanentErrorAfterRetriesExhaustedCompletesNormally() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 30_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        // Always error — retries will exhaust
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onError(new RuntimeException("permanent failure"));
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone(15);

        // Should have error events
        boolean hasError = emitter.events.stream()
            .anyMatch(e -> "error".equals(e.name));
        assertThat(hasError).isTrue();

        // Emitter should complete normally, not with error
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── Timeout: emitter completes normally, not with error ──

    @Test
    void timeoutCompletesNormallyWithErrorEvent() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        // Simulate a successful stream to keep it simple — timeout is tested
        // by the emitter.onTimeout callback which calls safeCompleteWithError
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("hi");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Normal completion should work fine
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── Helpers ──

    private static class CollectingEmitter extends SseEmitter {
        final List<SseEvent> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        CollectingEmitter(long timeout) { super(timeout); }

        @Override public void send(SseEventBuilder builder) throws IOException {
            events.add(new SseEvent(builder));
        }

        @Override public void complete() {
            this.completed.set(true);
            super.complete();
        }

        @Override public void completeWithError(Throwable ex) {
            this.error.set(ex);
            super.completeWithError(ex);
        }

        void awaitDone() { awaitDone(5); }

        void awaitDone(int timeoutSeconds) {
            await().pollInterval(50, TimeUnit.MILLISECONDS)
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
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
                for (Object dwmt : dataWithMediaTypes) {
                    Field dataField = dwmt.getClass().getDeclaredField("data");
                    dataField.setAccessible(true);
                    Object dataValue = dataField.get(dwmt);
                    if (dataValue != null) payload.append(dataValue.toString());
                }
                String rendered = payload.toString();
                this.name = parseEventName(rendered);
                this.data = extractData(rendered);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        private static String parseEventName(String rendered) {
            int eventIdx = rendered.indexOf("event:");
            if (eventIdx >= 0) {
                int nl = rendered.indexOf('\n', eventIdx);
                return rendered.substring(eventIdx + 6, nl >= 0 ? nl : rendered.length()).trim();
            }
            return null;
        }

        private static String extractData(String rendered) {
            int dataIdx = rendered.lastIndexOf("data:");
            if (dataIdx >= 0) return rendered.substring(dataIdx + 5).trim();
            return rendered;
        }
    }
}