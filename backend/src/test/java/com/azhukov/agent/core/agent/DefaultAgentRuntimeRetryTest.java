package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for P0 gap: Model API call retry behaviour.
 * <p>
 * Current behaviour ({@link DefaultAgentRuntime#runTurn}): there is NO retry on
 * model API calls. A single exception from {@code modelClient.complete()} aborts
 * the turn immediately, returning {@code TurnResult.error(...)}.
 * <p>
 * GAP: No retry mechanism exists for model API calls, even transient errors
 * (timeouts, connection resets, rate limits) cause immediate turn failure.
 * The {@link com.azhukov.agent.client.langchain4j.ErrorClassifier} and
 * {@code AgentProperties.error.retryAttempts} config exist but are not wired
 * into the runtime loop.
 */
class DefaultAgentRuntimeRetryTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private ToolExecutionService toolExecutionService;
    private ToolRegistry toolRegistry;
    private IterationBudget iterationBudget;
    private TurnStateManager turnStateManager;
    private TurnFinalizer turnFinalizer;
    private InterruptToken interruptToken;
    private SteerBuffer steerBuffer;
    private BackgroundReviewService backgroundReviewService;
    private ToolCallGuardrail guardrail;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        toolRegistry = mock(ToolRegistry.class);
        toolExecutionService = mock(ToolExecutionService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);
        iterationBudget = mock(IterationBudget.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        ContextReferenceService contextReferenceService = mock(ContextReferenceService.class);
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        guardrail = mock(ToolCallGuardrail.class);
        turnStateManager = mock(TurnStateManager.class);
        backgroundReviewService = mock(BackgroundReviewService.class);
        interruptToken = mock(InterruptToken.class);
        turnFinalizer = mock(TurnFinalizer.class);
        steerBuffer = mock(SteerBuffer.class);

        // Default stubs
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of());

        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(iterationBudget.recordToolExecution(any(), any(String.class), any(long.class))).thenReturn(snapshot);

        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));

        when(guardrail.isHalted()).thenReturn(false);

        runtime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer);
    }

    // ─── Current behaviour: no retry ───

    @Test
    @DisplayName("When modelClient.complete() throws, turn ends with error result (no retry)")
    void modelCallExceptionAbortsTurnImmediately() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        assertThat(result.error()).contains("connection refused");
    }

    @Test
    @DisplayName("When modelClient.complete() throws, modelClient.complete is called exactly once (no retry)")
    void modelCallExceptionResultsInSingleCall() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("timeout"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        verify(modelClient, times(1)).complete(any(List.class), any(List.class));
    }

    @Test
    @DisplayName("When modelClient.complete() throws, TurnFinalizer is called with success=false")
    void modelCallExceptionCallsFinalizerWithFailure() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("boom"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        verify(turnFinalizer).finalize(any(UUID.class), any(List.class), org.mockito.ArgumentMatchers.eq(false));
    }

    // ─── GAP: No retry on transient errors ───

    @Test
    @DisplayName("GAP: transient error (timeout) is not retried — single call, immediate failure")
    void gap_transientTimeoutIsNotRetried() {
        // Use RuntimeException wrapping timeout to avoid checked exception issues with Mockito
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("read timed out"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Document gap: timeout should be retryable but isn't
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class));
    }

    @Test
    @DisplayName("GAP: rate limit error is not retried — single call, immediate failure")
    void gap_rateLimitErrorIsNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("rate limit exceeded (429)"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Document gap: rate limit should trigger backoff+retry but doesn't
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class));
    }

    @Test
    @DisplayName("GAP: connection reset is not retried — single call, immediate failure")
    void gap_connectionResetIsNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenThrow(new RuntimeException("connection reset by peer"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class));
    }

    // ─── Successful path works normally ───

    @Test
    @DisplayName("When modelClient.complete() succeeds, turn completes normally")
    void modelCallSucceeds_TurnCompletes() {
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenReturn(ChatResponse.text("Hello! How can I help you?"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        verify(modelClient, times(1)).complete(any(List.class), any(List.class));
        verify(turnFinalizer).finalize(any(UUID.class), any(List.class), org.mockito.ArgumentMatchers.eq(true));
    }

    // ─── GAP: No retry even if second call would succeed ───

    @Test
    @DisplayName("GAP: if first call fails, second call (which would succeed) is never attempted")
    void gap_secondCallWouldSucceedButIsNeverAttempted() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class)))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    throw new RuntimeException("transient 503");
                }
                return ChatResponse.text("Success on retry");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Document gap: a retry mechanism would have succeeded here
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("transient 503");
        assertThat(callCount.get()).isEqualTo(1); // Only one call made
    }

    // ─── Tool call loop continues after successful model call ───

    @Test
    @DisplayName("When model returns tool calls, tools are executed and second model call is made")
    void modelReturnsToolCalls_thenSecondModelCallSucceeds() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger modelCallCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class)))
            .thenAnswer(inv -> {
                if (modelCallCount.incrementAndGet() == 1) {
                    return ChatResponse.toolCalls(List.of(toolCall));
                }
                return ChatResponse.text("The weather in London is sunny.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        assertThat(result.completed()).isTrue();
        assertThat(modelCallCount.get()).isEqualTo(2);
        verify(toolExecutionService, times(1)).execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any());
    }

    @Test
    @DisplayName("When model returns tool calls but second model call fails, turn ends with error")
    void modelReturnsToolCalls_thenSecondModelCallFails() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger modelCallCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class)))
            .thenAnswer(inv -> {
                if (modelCallCount.incrementAndGet() == 1) {
                    return ChatResponse.toolCalls(List.of(toolCall));
                }
                throw new RuntimeException("model crashed on second call");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        // Second model call failure also has no retry
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("model crashed on second call");
        assertThat(modelCallCount.get()).isEqualTo(2); // First succeeded, second failed
    }

    // ─── GAP: error config exists but is unused ───

    @Test
    @DisplayName("GAP: AgentProperties.error.retryAttempts is configured but not used by runtime")
    void gap_errorRetryConfigExistsButIsUnused() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        // The config exists with defaults
        assertThat(properties.getError().getRetryAttempts()).isEqualTo(3);
        assertThat(properties.getError().getRetryDelayMs()).isEqualTo(1000);
        assertThat(properties.getError().getBackoffMultiplier()).isEqualTo(2);
        // Also model-level retry config
        assertThat(properties.getModel().getMaxRetries()).isEqualTo(3);
        // GAP: these configs are never read by DefaultAgentRuntime
    }
}