package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
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
 * Tests for model API call retry behaviour.
 * <p>
 * The runtime wraps modelClient.complete() in a retry loop using ErrorClassifier.
 * RETRYABLE/RATE_LIMIT errors trigger jittered backoff and retry up to retryAttempts.
 * PERMANENT/BILLING/CONTEXT_OVERFLOW/CONTENT_POLICY errors fail immediately.
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
    private ErrorClassifier errorClassifier;

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
        // Set retry attempts to 3 (default) for predictable test behaviour
        properties.getError().setRetryAttempts(3);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        guardrail = mock(ToolCallGuardrail.class);
        turnStateManager = mock(TurnStateManager.class);
        backgroundReviewService = mock(BackgroundReviewService.class);
        interruptToken = mock(InterruptToken.class);
        turnFinalizer = mock(TurnFinalizer.class);
        steerBuffer = mock(SteerBuffer.class);
        errorClassifier = new ErrorClassifier();

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
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null);
    }

    // ─── Retry on transient errors ───

    @Test
    @DisplayName("When modelClient.complete() throws transient error (timeout), turn ends with error after retries exhausted")
    void transientTimeoutIsRetriedThenFails() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("read timed out"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Timeout is RETRYABLE → retried up to retryAttempts (3) + 1 initial = 4 total calls
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(4)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws rate limit error, turn ends with error after retries exhausted")
    void rateLimitErrorIsRetriedThenFails() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("rate limit exceeded (429)"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Rate limit is RATE_LIMIT → retried up to retryAttempts (3) + 1 initial = 4 total calls
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(4)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws connection reset, turn ends with error after retries exhausted")
    void connectionResetIsRetriedThenFails() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection reset by peer"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Connection reset is RETRYABLE → retried up to retryAttempts (3) + 1 initial = 4 total calls
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(4)).complete(any(List.class), any(List.class), any());
    }

    // ─── No retry on permanent errors ───

    @Test
    @DisplayName("When modelClient.complete() throws permanent error (invalid key), turn ends immediately with error")
    void permanentErrorNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("invalid API key"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Invalid API key is PERMANENT → no retry, single call
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        assertThat(result.error()).contains("invalid API key");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws billing error, turn ends immediately with error")
    void billingErrorNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("insufficient credits"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Billing error is PERMANENT → no retry, single call
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws context overflow error, turn ends immediately with error")
    void contextOverflowErrorNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("context length exceeded"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Context overflow is PERMANENT → no retry, single call
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws content policy error, turn ends immediately with error")
    void contentPolicyErrorNotRetried() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("content policy violation"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Content policy is PERMANENT → no retry, single call
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When modelClient.complete() throws, TurnFinalizer is called with success=false")
    void modelCallExceptionCallsFinalizerWithFailure() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        verify(turnFinalizer).finalize(any(UUID.class), any(List.class), org.mockito.ArgumentMatchers.eq(false));
    }

    // ─── Successful path works normally ───

    @Test
    @DisplayName("When modelClient.complete() succeeds, turn completes normally")
    void modelCallSucceeds_TurnCompletes() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("Hello! How can I help you?"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
        verify(turnFinalizer).finalize(any(UUID.class), any(List.class), org.mockito.ArgumentMatchers.eq(true));
    }

    // ─── Retry succeeds on second call ───

    @Test
    @DisplayName("When first call fails with transient error, second call (which succeeds) is attempted")
    void secondCallSucceedsAfterTransientFailure() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    throw new RuntimeException("transient 503");
                }
                return ChatResponse.text("Success on retry");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Retry mechanism succeeds here
        assertThat(result.completed()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(callCount.get()).isEqualTo(2); // First failed, second succeeded
    }

    // ─── Tool call loop continues after successful model call ───

    @Test
    @DisplayName("When model returns tool calls, tools are executed and second model call is made")
    void modelReturnsToolCalls_thenSecondModelCallSucceeds() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger modelCallCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
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
    @DisplayName("When model returns tool calls but second model call fails with transient error, it is retried")
    void modelReturnsToolCalls_thenSecondModelCallFailsWithRetry() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger modelCallCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (modelCallCount.incrementAndGet() == 1) {
                    return ChatResponse.toolCalls(List.of(toolCall));
                }
                if (modelCallCount.get() == 2) {
                    throw new RuntimeException("transient timeout");
                }
                return ChatResponse.text("The weather in London is sunny.");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        // Second model call failed with transient error → retried and succeeded
        assertThat(result.completed()).isTrue();
        assertThat(modelCallCount.get()).isEqualTo(3); // First succeeded, second failed, third succeeded
    }

    @Test
    @DisplayName("When model returns tool calls but second model call fails permanently, turn ends with error")
    void modelReturnsToolCalls_thenSecondModelCallFailsPermanently() {
        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"London\"}");
        AtomicInteger modelCallCount = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (modelCallCount.incrementAndGet() == 1) {
                    return ChatResponse.toolCalls(List.of(toolCall));
                }
                throw new RuntimeException("invalid API key");
            });

        when(toolExecutionService.execute(
            any(String.class), any(String.class), any(String.class),
            any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("Sunny, 22C"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "What's the weather?");

        // Second model call fails permanently → no retry
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("invalid API key");
        assertThat(modelCallCount.get()).isEqualTo(2); // First succeeded, second failed permanently
    }

    // ─── Error config is used by runtime ───

    @Test
    @DisplayName("AgentProperties.error.retryAttempts is configured and used by runtime")
    void errorRetryConfigIsUsed() {
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        // The config exists with defaults
        assertThat(properties.getError().getRetryAttempts()).isEqualTo(3);
        assertThat(properties.getError().getRetryDelayMs()).isEqualTo(1000);
        assertThat(properties.getError().getBackoffMultiplier()).isEqualTo(2);
        // Also model-level retry config
        assertThat(properties.getModel().getMaxRetries()).isEqualTo(3);
    }

    @Test
    @DisplayName("Retry count respects retryAttempts config — 1 retry means 2 total calls")
    void retryCountRespectsConfig() {
        // Create a runtime with retryAttempts=1
        AgentProperties properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryAttempts(1);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
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
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime customRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        customRuntime.runTurn(session, "Hello");

        // retryAttempts=1 → 1 initial + 1 retry = 2 total calls
        verify(modelClient, times(2)).complete(any(List.class), any(List.class), any());
    }
}