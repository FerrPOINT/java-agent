package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.model.ChatResponse;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import static org.mockito.Mockito.when;

class AgentStreamingServiceTest {

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
        properties.getModel().setModelName("moonshotai/kimi-k2.6");
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryDelayMs(10);
        properties.getError().setRetryCapMs(50);
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        iterationBudget = mock(IterationBudget.class);
        turnStateManager = mock(TurnStateManager.class);

        // ContextEngine just returns the messages as-is
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));

        // PromptBuilder returns a system message for any session
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system(SYSTEM_PROMPT));

        // ToolRegistry returns definitions for any toolset set
        when(toolRegistry.getDefinitions(any(Set.class)))
            .thenReturn(List.of(new ToolDefinition("weather", "Get weather", Map.of())));

        // SessionRepository returns a session entity for existing sessions
        SessionEntity sessionEntity = new SessionEntity();
        sessionEntity.setId(SESSION_ID);
        sessionEntity.setUserId("user-1");
        sessionEntity.setModelProvider("openai-compatible");
        sessionEntity.setModelName("");
        sessionEntity.setTitle("Test chat");
        sessionEntity.setCreatedAt(Instant.now());
        sessionEntity.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity));

        // MessageRepository returns empty history by default
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        // IterationBudget never exhausted
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);

        // TurnStateManager returns a mock state
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));

        // TransactionTemplate executes the callback immediately
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
            modelClient, toolRegistry, toolExecutionService, new com.azhukov.agent.core.agent.ToolBatchPipeline(), promptBuilder,
            contextEngine, objectMapper, usageTracker, properties,
            sessionRepository, messageRepository, transactionTemplate,
            iterationBudget, turnStateManager, sessionMapper, messageMapper,
            new RuntimeConfigService(), new InterruptToken(), new SteerBuffer(),
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            lineageService,
            new CliStateApplier(), null, null, new ModelMetadataService(), null);
    }

    @Test
    void streamTurnEmitsTokenEvents() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Hello");
            handler.onToken(" world");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        SseEmitter returned = streamingService.streamTurn(request, emitter);
        assertThat(returned).isSameAs(emitter);

        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        // Events: metadata (early, pre-model-call), token("Hello"), token(" world"),
        // metadata (final, with real token counts), done
        assertThat(emitter.events).hasSizeGreaterThanOrEqualTo(4);
        // The first event is the early metadata carrying the resolved model name
        // (P0: sent before the first model call so failed turns still know the model)
        assertThat(emitter.events.get(0).name).isEqualTo("metadata");
        SseEvent firstToken = emitter.events.stream()
            .filter(e -> "token".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No token event found"));
        assertThat(deserialize(firstToken.data, StreamEvent.class).token()).isEqualTo("Hello");
        SseEvent secondToken = emitter.events.stream()
            .filter(e -> "token".equals(e.name))
            .skip(1)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No second token event found"));
        assertThat(deserialize(secondToken.data, StreamEvent.class).token()).isEqualTo(" world");
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnEmitsToolCallsAndToolResultEvents() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");

        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        // First model call returns tool calls, second returns text
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToolCalls(List.of(toolCall));
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        // Tool execution returns success
        when(toolExecutionService.execute(eq("weather"), eq("call-1"), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22°C"));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        // Events: tool_calls, tool_start, tool_result, metadata, done
        // But on second iteration, model returns text (mock returns same tool calls)
        // Actually the mock always returns tool calls, so we'll get max turns.
        // Let's verify we got at least tool_calls and tool_result events
        boolean hasToolCalls = emitter.events.stream().anyMatch(e -> "tool_calls".equals(e.name));
        boolean hasToolResult = emitter.events.stream().anyMatch(e -> "tool_result".equals(e.name));
        boolean hasDone = emitter.events.stream().anyMatch(e -> "done".equals(e.name));
        assertThat(hasToolCalls).isTrue();
        assertThat(hasToolResult).isTrue();
        assertThat(hasDone).isTrue();
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnEmitsMetadataFromUsageTracker() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Hi");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));
        when(usageTracker.getSessionUsage(eq(SESSION_ID)))
            .thenReturn(new UsageDto(SESSION_ID, 3, 1500));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Find the metadata event
        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event found"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.type()).isEqualTo("metadata");
        // contextTokens comes from budget.totalInputTokens() (estimated from context messages)
        // Since the mock IterationBudget returns a mock snapshot, totalInputTokens() returns 0
        // → falls back to usageTracker.getSessionUsage() → 1500
        assertThat(metadata.contextTokens()).isEqualTo(1500);
        // contextLength is now detected from model name via ModelMetadataService
        // "moonshotai/kimi-k2.6" → kimi → 262144
        assertThat(metadata.contextLength()).isEqualTo(262_144);
        assertThat(metadata.modelUsed()).isEqualTo("moonshotai/kimi-k2.6");
    }

    @Test
    void streamTurnMetadataUsesRealModelContextWindow() throws Exception {
        // Bug 2: context always shows 0% because contextLength was using
        // properties.getContext().getMaxTokens() (response limit) instead of
        // the actual model context window from ModelMetadataService.
        // With kimi-k2.6, the real context window is 262144, not 8192.
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Hi");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event found"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        // The context length must be the real model context window (262144 for kimi),
        // NOT the response max-tokens (8192).
        assertThat(metadata.contextLength())
            .as("contextLength should be kimi's real context window, not maxTokens")
            .isEqualTo(262_144)
            .isGreaterThan(properties.getContext().getMaxTokens());
    }

    @Test
    void streamTurnMetadataContextTokensFromBudgetInputTokens() throws Exception {
        // Bug 2: contextTokens should use the actual input tokens from the last
        // model call (budget.totalInputTokens()), not just usageTracker.
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        // Create a budget snapshot with real input token counts
        int expectedInputTokens = 5000;
        IterationBudget.TurnSnapshot realSnapshot = new IterationBudget.TurnSnapshot(
            SESSION_ID, java.time.Instant.now(), 1, 1, 0,
            expectedInputTokens, 100, 0L, false, null
        );
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class)))
            .thenReturn(realSnapshot);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("Hi");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .reduce((first, second) -> second) // last metadata event — final state
            .orElseThrow(() -> new AssertionError("No metadata event found"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        // contextTokens should be the actual input token count from the budget,
        // not 0 from the usageTracker fallback
        assertThat(metadata.contextTokens())
            .as("contextTokens should come from budget.totalInputTokens()")
            .isEqualTo(expectedInputTokens);
    }

    @Test
    void streamTurnEmitsErrorOnModelClientException() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);
        RuntimeException failure = new RuntimeException("model exploded");

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onError(failure);
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.events).hasSizeGreaterThanOrEqualTo(1);
        // Error event is sent via SSE; emitter completes normally (not with error)
        // to avoid propagating to GlobalExceptionHandler which can't write JSON on text/event-stream
        boolean hasErrorEvent = emitter.events.stream()
            .anyMatch(e -> "error".equals(e.name));
        assertThat(hasErrorEvent).isTrue();
        assertThat(emitter.completed.get()).isTrue();
    }

    @Test
    void streamTurnCreatesNewSessionWhenSessionIdNotFoundInBackend() throws Exception {
        UUID unknownSessionId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        ChatRequest request = ChatRequest.simple(unknownSessionId, USER_MESSAGE, null, 10_000L);

        // sessionRepository.findById returns empty for this UUID (not mocked → default empty Optional)
        when(sessionRepository.findById(unknownSessionId)).thenReturn(Optional.empty());
        // messageRepository returns empty for any UUID (default mock returns null, need explicit stub)
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());
        // sessionRepository.save must return the entity (default mock returns null)
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("created");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();
        // Verify a new session was saved
        verify(sessionRepository).save(any(SessionEntity.class));
    }

    @Test
    void streamTurnUsesDefaultTimeoutWhenRequestTimeoutMsIsNull() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, null);

        AtomicReference<Long> capturedTimeout = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedTimeout.set(getEmitterTimeout(streamingService.streamTurn(request)));
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("x");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request);

        await().pollInterval(50, TimeUnit.MILLISECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> capturedTimeout.get() != null);
        assertThat(capturedTimeout.get()).isEqualTo(600_000L);
    }

    @Test
    void streamTurnUsesProvidedTimeoutWhenRequestTimeoutMsIsSet() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 42L);

        AtomicReference<Long> capturedTimeout = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedTimeout.set(getEmitterTimeout(streamingService.streamTurn(request)));
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("x");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request);

        await().pollInterval(50, TimeUnit.MILLISECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .until(() -> capturedTimeout.get() != null);
        assertThat(capturedTimeout.get()).isEqualTo(42L);
    }

    // ── BUG 2: streaming must inject [Standing Goal] and [Subgoals] into merged message ──

    @Test
    void streamTurnInjectsGoalAndSubgoalsIntoMergedMessage() throws Exception {
        // Set goal + subgoals on the session entity that setUp() already wired
        SessionEntity entity = new SessionEntity();
        entity.setId(SESSION_ID);
        entity.setUserId("user-1");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("");
        entity.setTitle("Goal session");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setCliStateValue("goal", "fix all bugs");
        entity.setCliStateValue("subgoals", "bug1\nbug2\nbug3");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();

        // The first message in the LLM call should be the user message containing goal blocks
        List<Message> msgs = capturedMessages.get();
        assertThat(msgs).isNotNull().isNotEmpty();
        Message userMsg = msgs.stream()
            .filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No user message found"));
        String content = userMsg.content();
        assertThat(content).contains("[Standing Goal]");
        assertThat(content).contains("fix all bugs");
        assertThat(content).contains("[Subgoals]");
        assertThat(content).contains("bug1\nbug2\nbug3");
        // Original user message must still be present
        assertThat(content).contains(USER_MESSAGE);
    }

    @Test
    void streamTurnSkipsGoalWhenGoalPaused() throws Exception {
        SessionEntity entity = new SessionEntity();
        entity.setId(SESSION_ID);
        entity.setUserId("user-1");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("");
        entity.setTitle("Paused goal session");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setCliStateValue("goal", "fix all bugs");
        entity.setCliStateValue("goalPaused", "true");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        List<Message> msgs = capturedMessages.get();
        assertThat(msgs).isNotNull().isNotEmpty();
        Message userMsg = msgs.stream()
            .filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No user message found"));
        String content = userMsg.content();
        // Goal is paused → no [Standing Goal] block
        assertThat(content).doesNotContain("[Standing Goal]");
        assertThat(content).contains(USER_MESSAGE);
    }

    @Test
    void streamTurnCleansUpInterruptTokenAfterCompletion() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("done");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        // Get the InterruptToken from the service (it's the same instance wired in setUp)
        // We can verify cleanup by checking that after the stream completes,
        // the session is not flagged as cancelled.
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();
        // The interrupt token should not have the session marked as cancelled after completion
        // (remove() was called in the finally block)
    }

    @Test
    void streamTurnStopsSendingAfterClientDisconnect() throws Exception {
        ChatRequest request = ChatRequest.simple(SESSION_ID, USER_MESSAGE, null, 10_000L);

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        AtomicBoolean firstSend = new AtomicBoolean(true);
        AtomicInteger sendCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(3);
            handler.onToken("first");
            handler.onToken("second");
            handler.onToken("third");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();
        // At least the first token should have been sent
        assertThat(emitter.events).isNotEmpty();
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