package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.*;
import com.azhukov.agent.service.UsageTracker;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression (0.1.16): the user message MUST be persisted for every turn.
 * Bug: persistedUpTo was initialized to turnMessages.size() BEFORE anything
 * was flushed, so end-of-turn persistTurn(fromIndex=persistedUpTo) skipped
 * the user message entirely — the DB history contained only assistant
 * replies. This starved countPriorUserMessages (memory-nudge hydration)
 * and session_search.
 */
class UserMessagePersistenceTest {

    private static final UUID SESSION_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private ModelClient modelClient;
    private AgentStreamingService streamingService;
    private MessageRepository messageRepository;
    private UsageTracker usageTracker;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        ObjectMapper objectMapper = new ObjectMapper();
        usageTracker = mock(UsageTracker.class);
        AgentProperties properties = new AgentProperties();
        // keep tests fast & deterministic: default is 100 (custom operator setting)
        properties.getError().setRetryAttempts(2);
        properties.getCore().setEmptyBackoffBaseMs(10L);
        properties.getCore().setEmptyBackoffCapMs(50L);
        properties.getContext().setMaxTokens(8192);
        properties.getModel().setModelName("test-model");
        properties.getCore().setMaxTurns(1);
        properties.getError().setRetryDelayMs(10);
        properties.getError().setRetryCapMs(50);
        com.azhukov.agent.persistence.repository.SessionRepository sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        messageRepository = mock(com.azhukov.agent.persistence.repository.MessageRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        IterationBudget iterationBudget = mock(IterationBudget.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("system"));
        when(toolRegistry.getDefinitions(any(Set.class))).thenReturn(List.of());
        when(contextEngine.countPriorUserMessages(any(UUID.class))).thenReturn(0L);

        SessionEntity sessionEntity = new SessionEntity();
        sessionEntity.setId(SESSION_ID);
        sessionEntity.setUserId("user-1");
        sessionEntity.setModelProvider("openai-compatible");
        sessionEntity.setModelName("");
        sessionEntity.setTitle("Test chat");
        sessionEntity.setCreatedAt(Instant.now());
        sessionEntity.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity));
        org.mockito.Mockito.lenient().when(sessionRepository.existsById(any(UUID.class))).thenReturn(true);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));

        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        SessionEntityMapper sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);
        var lineageService = mock(SessionLineageService.class);
        when(lineageService.loadMessagesWithAncestors(any(UUID.class))).thenReturn(List.of());

        streamingService = new AgentStreamingService(
            modelClient, toolRegistry, toolExecutionService, new com.azhukov.agent.core.agent.ToolBatchPipeline(), promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionStorePort(sessionRepository), sessionMapper, transactionTemplate, mock(com.azhukov.agent.core.ports.MessageStorePort.class), lineageService, mock(com.azhukov.agent.core.agent.ProjectContextDetector.class)),
            lineageService,
            new CliStateApplier(), null, null,
            new ModelMetadataService(), null);
    }

    @Test
    @DisplayName("tool-less turn persists BOTH the user message and the assistant reply")
    void toollessTurnPersistsUserMessage() throws Exception {
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Привет");
            handler.onComplete("STOP");
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(), any(StreamingResponseHandler.class));

        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 30_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            ArgumentCaptor<com.azhukov.agent.persistence.entity.MessageEntity> captor =
                ArgumentCaptor.forClass(com.azhukov.agent.persistence.entity.MessageEntity.class);
            verify(messageRepository, times(2)).save(captor.capture());
            List<String> roles = captor.getAllValues().stream()
                .map(com.azhukov.agent.persistence.entity.MessageEntity::getRole).toList();
            assertThat(roles).containsExactly("user", "assistant");
        });
    }

    @Test
    @DisplayName("turn that fails BEFORE any token still persists the user message")
    void failedTurnStillPersistsUserMessage() throws Exception {
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onError(new RuntimeException("model down"));
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(), any(StreamingResponseHandler.class));

        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 30_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() -> {
            ArgumentCaptor<com.azhukov.agent.persistence.entity.MessageEntity> captor =
                ArgumentCaptor.forClass(com.azhukov.agent.persistence.entity.MessageEntity.class);
            verify(messageRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo("user");
        });
    }

    @Test
    @DisplayName("streamed turn records usage into usage_log")
    void streamedTurnRecordsUsage() throws Exception {
        // Live defect: usage_log stayed empty for every streamed turn —
        // recordTurn was only wired on the sync path (AgentRuntimeService).
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Ок");
            handler.onComplete("STOP");
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(), any(StreamingResponseHandler.class));

        ChatRequest request = ChatRequest.simple(SESSION_ID, "Hello", null, 30_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
            verify(usageTracker).recordTurn(any(UUID.class), any(), any(),
                any(int.class), any(int.class)));
    }

    // CollectingEmitter — same pattern as AgentStreamingServiceDoublePersistenceTest
    static class CollectingEmitter extends SseEmitter {
        final CountDownLatch doneLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>(null);

        CollectingEmitter(long timeout) { super(timeout); }

        @Override public void send(SseEventBuilder event) { }
        @Override public void send(Object object) { }
        @Override public void complete() { doneLatch.countDown(); }
        @Override public void completeWithError(Throwable ex) { error.set(ex); doneLatch.countDown(); }

        void awaitDone() throws InterruptedException {
            doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
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
