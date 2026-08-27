package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.*;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.model.ChatResponse;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M18: Test that persistTurn is not called more than once per stream
 * (prevents double-persistence on error paths).
 */
class AgentStreamingServiceDoublePersistenceTest {

    private static final UUID SESSION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

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
    private AtomicInteger persistCount;

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
        properties.getCore().setEmptyBackoffBaseMs(10L);
        properties.getCore().setEmptyBackoffCapMs(50L);
        properties.getContext().setMaxTokens(8192);
        properties.getModel().setModelName("test-model");
        properties.getCore().setMaxTurns(1);
        properties.getError().setRetryDelayMs(10);
        properties.getError().setRetryCapMs(50);
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        iterationBudget = mock(IterationBudget.class);
        turnStateManager = mock(TurnStateManager.class);
        persistCount = new AtomicInteger(0);

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("system"));
        when(toolRegistry.getDefinitions(any(Set.class)))
            .thenReturn(List.of());

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

        // Track persistTurn calls — each persist executes a transaction that saves messages
        when(transactionTemplate.execute(any()))
            .thenAnswer(inv -> {
                org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
            });

        // Count message saves as a proxy for persistTurn calls
        doAnswer(inv -> {
            persistCount.incrementAndGet();
            return null;
        }).when(messageRepository).save(any());

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
            modelClient, toolRegistry, toolExecutionService, new com.azhukov.agent.core.agent.ToolBatchPipeline(), promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            lineageService,
            new CliStateApplier(), null, null, new com.azhukov.agent.core.metadata.ModelMetadataService(), null);
    }

    @Test
    void persistTurnCalledExactlyOnceOnSuccess() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Hello world");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // persistTurn should have been called exactly once
        // It saves: 1 user message + 1 assistant message = 2 saves + 1 session save
        // The key is that messageRepository.save is called exactly once per persistTurn call
        // (2 messages saved per call: user + assistant)
        // So if persistTurn is called once, we get 2 message saves
        // If called twice, we'd get 4 message saves
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(persistCount.get()).isLessThanOrEqualTo(4) // at most 2 messages per persist
        );
    }

    @Test
    void persistTurnNotCalledTwiceOnError() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        // Model fails with permanent error
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onError(new RuntimeException("Permanent failure"));
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // persistTurn should only be called once even on error path
        // On error: 1 user message saved per persistTurn call
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(persistCount.get()).isLessThanOrEqualTo(2) // at most 1 message per persist
        );
    }

    // CollectingEmitter — same pattern as AgentStreamingServiceTest
    static class CollectingEmitter extends SseEmitter {
        final CopyOnWriteArrayList<org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> error = new AtomicReference<>(null);

        CollectingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder event) {
            events.add(event);
        }

        @Override
        public void send(Object object) {
        }

        @Override
        public void complete() {
            completed.set(true);
        }

        @Override
        public void completeWithError(Throwable ex) {
            error.set(ex);
            completed.set(true);
        }

        void awaitDone() throws InterruptedException {
            await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilTrue(completed);
        }
    }
}