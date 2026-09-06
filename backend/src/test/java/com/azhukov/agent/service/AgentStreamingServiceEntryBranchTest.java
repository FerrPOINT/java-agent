package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.metadata.ModelMetadataService;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for AgentStreamingService streamTurn entry paths: null-session-id
 * creates a session, known session runs, queued prompt is consumed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentStreamingServiceEntryBranchTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock private ModelClient modelClient;
    @Mock private ToolRegistry toolRegistry;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private PromptBuilder promptBuilder;
    @Mock private ContextEngine contextEngine;
    @Mock private UsageTracker usageTracker;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private IterationBudget iterationBudget;
    @Mock private TurnStateManager turnStateManager;

    private AgentProperties properties;
    private AgentStreamingService streamingService;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getError().setRetryAttempts(1);
        properties.getCore().setEmptyBackoffBaseMs(5L);
        properties.getCore().setEmptyBackoffCapMs(10L);
        properties.getContext().setMaxTokens(8192);
        properties.getModel().setModelName("test-model");
        properties.getCore().setMaxTurns(2);
        properties.getError().setRetryDelayMs(5);
        properties.getError().setRetryCapMs(10);

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("sys"));
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
        when(sessionRepository.findById(any(UUID.class))).thenReturn(Optional.of(sessionEntity));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), anyInt(), anyInt())).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), anyString(), anyLong())).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), anyInt()))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));

        when(transactionTemplate.execute(any()))
            .thenAnswer(inv -> {
                org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(null);
            });
        doAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallbackWithoutResult cb = inv.getArgument(0);
            cb.doInTransaction(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // Model streams a single token then completes
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("done");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        SessionEntityMapper sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);

        com.azhukov.agent.core.agent.SessionLineageService lineageService = mock(com.azhukov.agent.core.agent.SessionLineageService.class);
        when(lineageService.loadMessagesWithAncestors(any(UUID.class))).thenReturn(java.util.Collections.emptyList());

        streamingService = new AgentStreamingService(
            modelClient, toolRegistry, toolExecutionService, new com.azhukov.agent.core.agent.ToolBatchPipeline(), promptBuilder,
            contextEngine, new ObjectMapper(), usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new com.azhukov.agent.core.agent.InterruptToken(), new com.azhukov.agent.core.agent.SteerBuffer(),
            new com.azhukov.agent.core.agent.TokenEstimator(), new com.azhukov.agent.core.agent.ToolResultFormatter(),
            new com.azhukov.agent.core.agent.AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            lineageService,
            new com.azhukov.agent.core.agent.CliStateApplier(), null, null, new ModelMetadataService(), null);
    }

    @Test
    void streamTurnWithoutSessionIdRunsToCompletion() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(ChatRequest.simple(null, "Hello", null, 5_000L), emitter);
        emitter.awaitDone();
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    @Test
    void streamTurnWithKnownSessionIdRunsToCompletion() throws Exception {
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(ChatRequest.simple(SESSION_ID, "Hello again", null, 5_000L), emitter);
        emitter.awaitDone();
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnWithQueuedPromptRunsToCompletion() throws Exception {
        // The full consume-and-clear contract is unit-covered in
        // CliStateApplierQueuedPromptTest; here we prove the streamed turn with a
        // queued prompt present still completes cleanly end to end.
        SessionEntity entity = sessionRepository.findById(SESSION_ID).orElseThrow();
        entity.setCliStateValue("queuedPrompt", "follow-up question");
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(ChatRequest.simple(SESSION_ID, "main", null, 5_000L), emitter);
        emitter.awaitDone();
        assertThat(emitter.completed.get()).isTrue();
    }

    // ── minimal collecting emitter (same pattern as AgentStreamingServiceSseErrorTest) ──

    private static final class CollectingEmitter extends SseEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        CollectingEmitter(long timeout) { super(timeout); }

        @Override public void send(SseEventBuilder builder) throws IOException {
            events.add("event");
            super.send(builder);
        }

        @Override public void complete() {
            this.completed.set(true);
            super.complete();
        }

        @Override public void completeWithError(Throwable ex) {
            this.error.set(ex);
            super.completeWithError(ex);
        }

        void awaitDone() {
            awaitDone(20);
        }

        void awaitDone(long seconds) {
            long deadline = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < deadline && !completed.get() && error.get() == null) {
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
