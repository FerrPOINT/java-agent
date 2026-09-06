package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.*;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnState;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for commentary SSE event emission in the streaming path
 * ({@link AgentStreamingService}).
 * <p>
 * S-3 gap: the streaming path emits a {@code StreamEvent("commentary", text, ...)}
 * when the LLM returns BOTH text AND tool calls. This test verifies:
 * <ol>
 *   <li>Commentary event IS emitted when LLM returns text + tool calls</li>
 *   <li>Commentary event is NOT emitted when there is no text content (tool calls only)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentaryStreamingTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ModelClient modelClient;
    private ToolExecutionService toolExecutionService;
    private AgentStreamingService streamingService;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryDelayMs(10);
        properties.getError().setRetryCapMs(50);
        properties.getModel().setModelName("test-model");

        modelClient = mock(ModelClient.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        toolExecutionService = mock(ToolExecutionService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        ObjectMapper objectMapper = new ObjectMapper();
        UsageTracker usageTracker = mock(UsageTracker.class);

        com.azhukov.agent.persistence.repository.SessionRepository sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        com.azhukov.agent.persistence.repository.MessageRepository messageRepository = mock(com.azhukov.agent.persistence.repository.MessageRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        IterationBudget iterationBudget = mock(IterationBudget.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a helpful assistant."));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.of(new ToolDefinition("weather", "Get weather", java.util.Map.of())));

        SessionEntity sessionEntity = new SessionEntity();
        sessionEntity.setId(SESSION_ID);
        sessionEntity.setUserId("user-1");
        sessionEntity.setModelProvider("openai-compatible");
        sessionEntity.setModelName("");
        sessionEntity.setTitle("Test chat");
        sessionEntity.setCreatedAt(Instant.now());
        sessionEntity.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(TurnState.class));

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        SessionEntityMapper sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);

        SessionLineageService lineageService = mock(SessionLineageService.class);
        when(lineageService.loadMessagesWithAncestors(any(UUID.class)))
            .thenReturn(java.util.Collections.emptyList());

        streamingService = new AgentStreamingService(
            modelClient, toolRegistry, toolExecutionService, new com.azhukov.agent.core.agent.ToolBatchPipeline(), promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionStorePort(sessionRepository), sessionMapper, transactionTemplate, mock(com.azhukov.agent.core.ports.MessageStorePort.class), mock(SessionLineageService.class)),
            lineageService,
            new CliStateApplier(), null, null,
            new ModelMetadataService(), null);
    }

    @Test
    @DisplayName("5. AgentStreamingService emits StreamEvent('commentary', text) when LLM returns text AND tool calls (streaming)")
    void streamingEmitsCommentaryEvent_whenTextAndToolCalls() throws Exception {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Tokyo\"}");
        AtomicInteger streamCallCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            int n = streamCallCount.incrementAndGet();
            if (n == 1) {
                handler.onToken("Let me check Tokyo weather.");
                handler.onToolCalls(List.of(toolCall));
                handler.onComplete();
            } else {
                handler.onToken("Tokyo is 25°C and sunny.");
                handler.onComplete();
            }
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("25°C, sunny"));

        ChatRequest request = ChatRequest.simple(SESSION_ID, "Weather in Tokyo?", null, 30_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.doneLatch.getCount()).isZero();

        boolean hasCommentary = emitter.events.stream()
            .anyMatch(e -> "commentary".equals(e.name));
        assertThat(hasCommentary)
            .as("Streaming should emit 'commentary' event when LLM returns text + tool calls")
            .isTrue();

        SseEvent commentaryEvent = emitter.events.stream()
            .filter(e -> "commentary".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No commentary event found"));
        StreamEvent commentary = deserialize(commentaryEvent.data, StreamEvent.class);
        assertThat(commentary.type()).isEqualTo("commentary");
        assertThat(commentary.token()).contains("Tokyo weather");
    }

    @Test
    @DisplayName("6. AgentStreamingService does NOT emit commentary event when no text content (tool calls only)")
    void streamingDoesNotEmitCommentary_whenNoTextContent() throws Exception {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"NYC\"}");
        AtomicInteger streamCallCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            int n = streamCallCount.incrementAndGet();
            if (n == 1) {
                // No onToken calls — just tool calls
                handler.onToolCalls(List.of(toolCall));
                handler.onComplete();
            } else {
                handler.onToken("NYC is rainy.");
                handler.onComplete();
            }
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Rainy"));

        ChatRequest request = ChatRequest.simple(SESSION_ID, "Weather in NYC?", null, 30_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.doneLatch.getCount()).isZero();

        boolean hasCommentary = emitter.events.stream()
            .anyMatch(e -> "commentary".equals(e.name));
        assertThat(hasCommentary)
            .as("Streaming should NOT emit 'commentary' when there is no text content")
            .isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static <T> T deserialize(String json, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(json, type);
    }

    private static class CollectingEmitter extends SseEmitter {
        final List<SseEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch doneLatch = new CountDownLatch(1);
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
            this.doneLatch.countDown();
            super.complete();
        }

        @Override
        public void completeWithError(Throwable ex) {
            this.error.set(ex);
            this.doneLatch.countDown();
            super.completeWithError(ex);
        }

        void awaitDone() {
            try {
                doneLatch.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
                    Field dataField = getField(dwmt.getClass(), "data");
                    Object dataValue = dataField.get(dwmt);
                    if (dataValue != null) {
                        payload.append(dataValue.toString());
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

    private com.azhukov.agent.core.ports.SessionStorePort sessionStorePort(com.azhukov.agent.persistence.repository.SessionRepository sessionRepository) {
        com.azhukov.agent.core.ports.SessionStorePort port = mock(com.azhukov.agent.core.ports.SessionStorePort.class);
        org.mockito.Mockito.lenient().when(port.findById(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> sessionRepository.findById(inv.getArgument(0)));
        org.mockito.Mockito.lenient().when(port.save(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> sessionRepository.save(inv.getArgument(0)));
        org.mockito.Mockito.lenient().when(port.findChildSessions(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> java.util.List.of());
        org.mockito.Mockito.lenient().doAnswer(inv -> null).when(port)
            .insertSessionRow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        return port;
    }

}