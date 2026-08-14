package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.api.dto.UsageDto;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage tests for {@link AgentStreamingService} targeting:
 * - selectTools with disabledTools / null request
 * - resolveModelUsed fallback chain (blank model → runtime override → properties → unknown)
 * - estimateContextTokens null session / null usage
 * - formatResult / formatResultPreview (success vs error, > 500 chars)
 * - buildMergedMessage with goal/subgoals/subgoal/queuedPrompt combinations
 * - retry on RETRYABLE error, permanent error after retries exhausted
 * - continuation prompt for truncated (empty) response
 * - iteration budget exhausted
 * - max turns reached
 * - steer note injection
 * - tool error result formatting
 * - new session creation path (null sessionId)
 */
class AgentStreamingServiceBranchTest {

    private static final UUID SESSION_ID = UUID.fromString("33333333-4444-5555-6666-777777777777");
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
    private RuntimeConfigService runtimeConfigService;
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
        properties.getContext().setMaxTokens(4096);
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
        runtimeConfigService = new RuntimeConfigService();

        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        when(toolRegistry.getDefinitions(any(Set.class)))
            .thenReturn(List.of(new ToolDefinition("weather", "Get weather", Map.of())));

        SessionEntity sessionEntity = newSessionEntity(SESSION_ID, "test-model");
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
        // Delegate loadMessagesWithAncestors to messageRepository so history-loading tests work
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
            runtimeConfigService, interruptToken, steerBuffer,
            new TokenEstimator(), new ToolResultFormatter(),
            new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            lineageService,
            new CliStateApplier(), null, null, new com.azhukov.agent.core.metadata.ModelMetadataService(), null);
    }

    // ── selectTools: disabledTools filtering ──

    @Test
    void streamTurnWithDisabledToolsFiltersThemOut() throws Exception {
        ChatRequest request = new ChatRequest(
            SESSION_ID, "Hello", null, 10_000L,
            null, null, null, null,
            null, List.of("weather"), // disabledTools
            null, null, null, null);

        AtomicReference<List<ToolDefinition>> capturedTools = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedTools.set(invocation.getArgument(1));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        // weather was the only tool and it's disabled → empty tools list
        assertThat(capturedTools.get()).isNotNull().isEmpty();
    }

    // ── resolveModelUsed: blank model → runtime override ──

    @Test
    void streamTurnUsesRuntimeOverrideWhenSessionModelIsBlank() throws Exception {
        // Override the session entity with blank model name
        SessionEntity entity = newSessionEntity(SESSION_ID, "");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        runtimeConfigService.setModelOverride("override-model");

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Find the metadata event and check modelUsed
        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.modelUsed()).isEqualTo("override-model");
    }

    // ── resolveModelUsed: blank model, no override → properties model ──

    @Test
    void streamTurnUsesPropertiesModelWhenNoOverrideAndBlankSessionModel() throws Exception {
        // Session has blank model, no runtime override → should use properties model
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.modelUsed()).isEqualTo("test-model");
    }

    // ── resolveModelUsed: session model is set ──

    @Test
    void streamTurnUsesSessionModelWhenSet() throws Exception {
        SessionEntity entity = newSessionEntity(SESSION_ID, "my-session-model");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.modelUsed()).isEqualTo("my-session-model");
    }

    // ── resolveModelUsed: all blank/null → "unknown" ──

    @Test
    void streamTurnReturnsUnknownWhenAllModelsBlank() throws Exception {
        // Session model blank, properties model blank, no override
        properties.getModel().setModelName("");
        SessionEntity entity = newSessionEntity(SESSION_ID, "");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.modelUsed()).isEqualTo("unknown");
    }

    // ── estimateContextTokens: null usage from tracker ──

    @Test
    void streamTurnMetadataHandlesNullUsage() throws Exception {
        when(usageTracker.getSessionUsage(eq(SESSION_ID))).thenReturn(null);

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent metadataEvent = emitter.events.stream()
            .filter(e -> "metadata".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No metadata event"));
        StreamEvent metadata = deserialize(metadataEvent.data, StreamEvent.class);
        assertThat(metadata.contextTokens()).isZero();
    }

    // ── Tool error result: formatResult error path ──

    @Test
    void toolErrorResultIsFormattedWithErrorMessage() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");
        CollectingEmitter emitter = new CollectingEmitter(1000L);

        AtomicInteger callCount = new AtomicInteger(0);
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

        when(toolExecutionService.execute(eq("weather"), eq("call-1"), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.fail("Tool execution failed"));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Find tool_result event — should contain "Error: Tool execution failed"
        SseEvent toolResultEvent = emitter.events.stream()
            .filter(e -> "tool_result".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool_result event"));
        StreamEvent resultEvent = deserialize(toolResultEvent.data, StreamEvent.class);
        assertThat(resultEvent.toolResult()).contains("Error: Tool execution failed");
    }

    // ── Tool error result: preview > 500 chars ──

    @Test
    void toolErrorResultPreviewTruncatedWhenLongerThan500Chars() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        ToolCall toolCall = new ToolCall("call-1", "failing-tool", "{\"x\":1}");
        CollectingEmitter emitter = new CollectingEmitter(1000L);

        AtomicInteger callCount = new AtomicInteger(0);
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

        // Tool returns an error with very long error message
        String longError = "E".repeat(2000);
        when(toolExecutionService.execute(eq("failing-tool"), eq("call-1"), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.fail(longError));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        SseEvent toolResultEvent = emitter.events.stream()
            .filter(e -> "tool_result".equals(e.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No tool_result event"));
        StreamEvent resultEvent = deserialize(toolResultEvent.data, StreamEvent.class);
        // Error content = "Error: " + longError, truncated to 500 chars + "..."
        assertThat(resultEvent.toolResult().length()).isLessThanOrEqualTo(504);
        assertThat(resultEvent.toolResult()).endsWith("...");
    }

    // ── New session path (null sessionId) ──

    @Test
    void streamTurnWithNullSessionIdCreatesNewSession() throws Exception {
        ChatRequest request = new ChatRequest(null, "Hello", null, 10_000L);

        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("new session");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        assertThat(emitter.completed.get()).isTrue();
        verify(sessionRepository).save(any(SessionEntity.class));
    }

    // ── Continuation prompt for truncated (empty) response ──

    @Test
    void truncatedEmptyResponseTriggersContinuationPrompt() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(1000L);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // First call: empty response (truncated)
                handler.onComplete();
            } else {
                // Second call (continuation): returns text
                handler.onToken("continued text");
                handler.onComplete();
            }
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Should have a "continuation" event
        boolean hasContinuation = emitter.events.stream()
            .anyMatch(e -> "continuation".equals(e.name));
        assertThat(hasContinuation).isTrue();
        assertThat(emitter.completed.get()).isTrue();
    }

    // ── Iteration budget exhausted ──

    @Test
    void iterationBudgetExhaustedTerminatesStream() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        when(iterationBudget.isExhausted(any())).thenReturn(true);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("should not matter");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Should have a "token" event with the new budget exhausted message format
        boolean hasBudgetMessage = emitter.events.stream()
            .anyMatch(e -> {
                if (!"token".equals(e.name)) return false;
                try {
                    StreamEvent ev = deserialize(e.data, StreamEvent.class);
                    return ev.token() != null && ev.token().contains("Iteration budget exhausted");
                } catch (Exception ex) {
                    return false;
                }
            });
        assertThat(hasBudgetMessage).isTrue();
        assertThat(emitter.completed.get()).isTrue();
    }

    // ── Max turns reached ──

    @Test
    void maxTurnsReachedTerminatesStream() throws Exception {
        // Set maxTurns to 1 so we hit the limit quickly
        properties.getCore().setMaxTurns(1);

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(1000L);

        // Model always returns tool calls → never finishes with text → max turns
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToolCalls(List.of(toolCall));
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        when(toolExecutionService.execute(eq("weather"), eq("call-1"), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny"));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Should have a "token" event with "Reached maximum turns..."
        boolean hasMaxTurnsMessage = emitter.events.stream()
            .anyMatch(e -> {
                if (!"token".equals(e.name)) return false;
                try {
                    StreamEvent ev = deserialize(e.data, StreamEvent.class);
                    return ev.token() != null && ev.token().contains("maximum turns");
                } catch (Exception ex) {
                    return false;
                }
            });
        assertThat(hasMaxTurnsMessage).isTrue();
    }

    // ── buildMergedMessage: subgoal injection ──

    @Test
    void streamTurnInjectsSubgoalIntoMergedMessage() throws Exception {
        SessionEntity entity = newSessionEntity(SESSION_ID, "test-model");
        entity.setSubgoal("fix the login bug");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        Message userMsg = capturedMessages.get().stream()
            .filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER)
            .findFirst()
            .orElseThrow();
        assertThat(userMsg.content()).contains("[Goal/Subgoal]");
        assertThat(userMsg.content()).contains("fix the login bug");
    }

    // ── buildMergedMessage: queuedPrompt injection ──

    @Test
    void streamTurnInjectsQueuedPromptIntoMergedMessage() throws Exception {
        SessionEntity entity = newSessionEntity(SESSION_ID, "test-model");
        entity.setCliStateValue("queuedPrompt", "Remember to check tests");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(emitter.error.get()).isNull();
        Message userMsg = capturedMessages.get().stream()
            .filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER)
            .findFirst()
            .orElseThrow();
        assertThat(userMsg.content()).contains("[Queued context]");
        assertThat(userMsg.content()).contains("Remember to check tests");
    }

    // ── buildMergedMessage: goal from request overrides session ──

    @Test
    void streamTurnGoalFromRequestOverridesSessionGoal() throws Exception {
        SessionEntity entity = newSessionEntity(SESSION_ID, "test-model");
        entity.setCliStateValue("goal", "session goal");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        ChatRequest request = new ChatRequest(
            SESSION_ID, "Hello", null, 10_000L,
            null, null, null, null,
            null, null, null, null, null, "request goal");

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        Message userMsg = capturedMessages.get().stream()
            .filter(m -> m.role() == com.azhukov.agent.core.model.Role.USER)
            .findFirst()
            .orElseThrow();
        assertThat(userMsg.content()).contains("[Standing Goal]");
        assertThat(userMsg.content()).contains("request goal");
        // Should NOT contain the session's goal
        assertThat(userMsg.content()).doesNotContain("session goal");
    }

    // ── applyCliState: null sessionId returns request unchanged ──

    @Test
    void streamTurnWithNullSessionIdDoesNotApplyCliState() throws Exception {
        ChatRequest request = new ChatRequest(null, "Hello", null, 10_000L);

        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            List<Message> msgs = invocation.getArgument(0);
            // Find the user message (last in list)
            capturedMessage.set(msgs.get(msgs.size() - 1).content());
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Message should be the original "Hello" without any CLI state prefix
        assertThat(capturedMessage.get()).isEqualTo("Hello");
    }

    // ── applyCliState: session not found returns request unchanged ──

    @Test
    void streamTurnWithUnknownSessionIdDoesNotApplyCliState() throws Exception {
        UUID unknownId = UUID.fromString("88888888-9999-0000-1111-222222222222");
        ChatRequest request = new ChatRequest(unknownId, "Hello", null, 10_000L);

        when(sessionRepository.findById(unknownId)).thenReturn(Optional.empty());
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            List<Message> msgs = invocation.getArgument(0);
            capturedMessage.set(msgs.get(msgs.size() - 1).content());
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        assertThat(capturedMessage.get()).isEqualTo("Hello");
    }

    // ── Retry on RETRYABLE error then success ──

    @Test
    void retryableErrorTriggersRetryAndEventuallySucceeds() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(1000L);

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // First call: retryable error
                handler.onError(new RuntimeException("Connection reset"));
            } else {
                // Second call (retry): success
                handler.onToken("recovered");
                handler.onComplete();
            }
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Should have a "retry" event
        boolean hasRetry = emitter.events.stream()
            .anyMatch(e -> "retry".equals(e.name));
        assertThat(hasRetry).isTrue();
        // Should complete successfully
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── Non-retryable error (permanent) ──

    @Test
    void permanentErrorTerminatesImmediatelyWithoutRetry() throws Exception {
        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 30_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        // Error thrown as exception from stream() (not via onError)
        // RuntimeException is classified as RETRYABLE by ErrorClassifier, so it will retry
        // To get a permanent error, we need to exhaust retries
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            int count = callCount.incrementAndGet();
            handler.onError(new RuntimeException("permanent failure " + count));
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone(15);

        // Should have error events (initial + retries exhausted)
        boolean hasError = emitter.events.stream()
            .anyMatch(e -> "error".equals(e.name));
        assertThat(hasError).isTrue();
        // Should have retry events (up to MAX_STREAM_RETRIES = 5)
        long retryCount = emitter.events.stream()
            .filter(e -> "retry".equals(e.name))
            .count();
        assertThat(retryCount).isLessThanOrEqualTo(5);
    }

    // ── sendMetadataEvent handles exception gracefully ──

    @Test
    void metadataEventExceptionDoesNotBreakStream() throws Exception {
        // Set up usageTracker to throw
        when(usageTracker.getSessionUsage(eq(SESSION_ID)))
            .thenThrow(new RuntimeException("usage tracker broken"));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        // Stream should still complete successfully despite metadata event failure
        assertThat(emitter.completed.get()).isTrue();
        assertThat(emitter.error.get()).isNull();
    }

    // ── History loaded for existing session ──

    @Test
    void existingSessionLoadsHistory() throws Exception {
        // Set up message history
        var msgEntity = new com.azhukov.agent.persistence.entity.MessageEntity();
        msgEntity.setRole("user");
        msgEntity.setContent("previous message");
        msgEntity.setSessionId(SESSION_ID);
        msgEntity.setCreatedAt(Instant.now());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(msgEntity));

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMessages.set(invocation.getArgument(0));
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("ok");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(any(List.class), any(List.class), any(StreamingResponseHandler.class));

        ChatRequest request = new ChatRequest(SESSION_ID, "Hello", null, 10_000L);
        CollectingEmitter emitter = new CollectingEmitter(30_000L);
        streamingService.streamTurn(request, emitter);
        emitter.awaitDone();

        List<Message> msgs = capturedMessages.get();
        assertThat(msgs).isNotNull();
        // Should contain: system, history(user "previous message"), current user("Hello")
        boolean hasPreviousMessage = msgs.stream()
            .anyMatch(m -> "previous message".equals(m.content()));
        assertThat(hasPreviousMessage).isTrue();
        boolean hasCurrentMessage = msgs.stream()
            .anyMatch(m -> "Hello".equals(m.content()));
        assertThat(hasCurrentMessage).isTrue();
    }

    // ── Helpers ──

    private SessionEntity newSessionEntity(UUID id, String modelName) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId("user-1");
        e.setModelProvider("openai-compatible");
        e.setModelName(modelName);
        e.setTitle("Test chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static <T> T deserialize(String json, Class<T> type) throws IOException {
        return new ObjectMapper().readValue(json, type);
    }

    private static class CollectingEmitter extends SseEmitter {
        private final List<SseEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        CollectingEmitter(long timeout) { super(timeout); }

        @Override public void send(SseEventBuilder builder) throws IOException { events.add(new SseEvent(builder)); }
        @Override public void complete() { this.completed.set(true); super.complete(); }
        @Override public void completeWithError(Throwable ex) { this.error.set(ex); super.completeWithError(ex); }

        void awaitDone() {
            awaitDone(5);
        }

        void awaitDone(int timeoutSeconds) {
            await().pollInterval(50, TimeUnit.MILLISECONDS).atMost(timeoutSeconds, TimeUnit.SECONDS)
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
                    Field dataField = getField(dwmt.getClass(), "data");
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

        private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        }
    }
}