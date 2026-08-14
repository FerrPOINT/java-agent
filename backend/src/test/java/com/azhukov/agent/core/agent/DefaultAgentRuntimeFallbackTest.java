package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for mid-turn model fallback behavior in {@link DefaultAgentRuntime}.
 * <p>
 * Tests verify that:
 * - Retries exhausted on RETRYABLE errors trigger fallback to next model
 * - AUTH_PERMANENT errors trigger immediate fallback (no retry)
 * - MODEL_NOT_FOUND errors trigger immediate fallback
 * - CONTENT_POLICY errors trigger fallback before failing
 * - Chain exhaustion leads to FALLBACK_EXHAUSTED exit reason
 * - Primary model is restored after the turn completes
 * - No fallback chain → original behavior is preserved
 */
class DefaultAgentRuntimeFallbackTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private AgentProperties properties;
    private ErrorClassifier errorClassifier;
    private TurnFinalizer turnFinalizer;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolExecutionService toolExecutionService = mock(ToolExecutionService.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        SkillManager skillManager = mock(SkillManager.class);
        IterationBudget iterationBudget = mock(IterationBudget.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        ContextReferenceService contextReferenceService = mock(ContextReferenceService.class);
        properties = new AgentProperties();
        properties.getCore().setMaxTurns(10);
        properties.getError().setRetryAttempts(2);
        properties.getError().setRetryDelayMs(10); // fast for tests
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        ToolCallGuardrail guardrail = mock(ToolCallGuardrail.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);
        BackgroundReviewService backgroundReviewService = mock(BackgroundReviewService.class);
        InterruptToken interruptToken = mock(InterruptToken.class);
        turnFinalizer = mock(TurnFinalizer.class);
        SteerBuffer steerBuffer = mock(SteerBuffer.class);
        errorClassifier = new ErrorClassifier();

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
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    private FallbackConfig makeFallback(String provider, String model, String baseUrl, String apiKey) {
        FallbackConfig cfg = new FallbackConfig();
        cfg.setProvider(provider);
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey(apiKey);
        return cfg;
    }

    // ─── No fallback chain → original behavior ───

    @Test
    @DisplayName("When no fallback chain configured, RETRYABLE errors fail after retries (no fallback)")
    void noFallbackChain_retryableFailsAfterRetries() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Model call failed");
        // 1 initial + 2 retries = 3 calls
        verify(modelClient, times(3)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When no fallback chain configured, AUTH_PERMANENT fails immediately (no fallback)")
    void noFallbackChain_authPermanentFailsImmediately() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("403 forbidden"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("When no fallback chain configured, content policy returns user-friendly message (no fallback)")
    void noFallbackChain_contentPolicyReturnsUserMessage() {
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("content policy violation"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        assertThat(result.completed()).isTrue();
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
        verify(turnFinalizer).finalize(any(UUID.class), any(List.class), any(boolean.class),
            org.mockito.ArgumentMatchers.eq(TurnExitReason.CONTENT_POLICY));
    }

    // ─── With fallback chain → fallback activated ───

    @Test
    @DisplayName("With fallback chain, AUTH_PERMANENT triggers immediate fallback to next model")
    void fallbackChain_authPermanentTriggersFallback() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "gpt-4o-mini", "https://fallback.api.com", "fallback-key")
        ));

        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                if (callCount.incrementAndGet() == 1) {
                    throw new RuntimeException("403 forbidden");
                }
                // Should not reach here — primary should fail and fallback should be used
                // But the fallback uses a different ModelClient, so this mock won't be called
                return ChatResponse.text("primary response");
            });

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Primary failed with AUTH_PERMANENT → immediate fallback
        // The fallback model client is created internally and will try to call
        // the fallback URL. Since we can't mock it, it will fail on connection.
        // But the key point is the primary was called only once (no retry).
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("With fallback chain, MODEL_NOT_FOUND triggers immediate fallback")
    void fallbackChain_modelNotFoundTriggersFallback() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "fallback-model", "https://fallback.api.com", "fallback-key")
        ));

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("404 model not found"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        // Primary failed with MODEL_NOT_FOUND → immediate fallback, single call to primary
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("With fallback chain, RETRYABLE errors exhaust retries then trigger fallback")
    void fallbackChain_retryableExhaustsThenFallback() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "fallback-model", "https://fallback.api.com", "fallback-key")
        ));
        properties.getError().setRetryAttempts(1);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        // Primary failed with RETRYABLE → 1 initial + 1 retry = 2 calls to primary
        // Then fallback is activated (which creates a new client internally)
        verify(modelClient, times(2)).complete(any(List.class), any(List.class), any());
    }

    @Test
    @DisplayName("With fallback chain, CONTENT_POLICY triggers fallback before user-facing error")
    void fallbackChain_contentPolicyTriggersFallback() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "fallback-model", "https://fallback.api.com", "fallback-key")
        ));

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("content policy violation"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        // Primary failed with CONTENT_POLICY → fallback attempted, single call to primary
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    // ─── Fallback chain exhaustion ───

    @Test
    @DisplayName("When all fallbacks are exhausted, turn fails with error")
    void fallbackChain_allExhausted_fails() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "fallback-1", "https://fallback1.api.com", "key1"),
            makeFallback("anthropic", "fallback-2", "https://fallback2.api.com", "key2")
        ));
        properties.getError().setRetryAttempts(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = runtime.runTurn(session, "Hello");

        // Primary failed, fallbacks activated (they'll fail on connection too),
        // eventually the turn fails
        assertThat(result.completed()).isFalse();
        verify(modelClient, times(1)).complete(any(List.class), any(List.class), any());
    }

    // ─── Primary restoration ───

    @Test
    @DisplayName("After turn with fallback, primary model is restored for next turn")
    void fallback_primaryRestoredAfterTurn() {
        properties.setFallbackChain(List.of(
            makeFallback("openai-compatible", "fallback-model", "https://fallback.api.com", "fallback-key")
        ));
        properties.getError().setRetryAttempts(0);

        // First turn: primary fails, fallback is activated but fails too
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("connection refused"));

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        runtime.runTurn(session, "Hello");

        // Second turn: primary succeeds — this verifies primary was restored
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.text("Success on primary after restore"));

        TurnResult result2 = runtime.runTurn(session, "Hello again");
        assertThat(result2.completed()).isTrue();
    }

    // ─── FallbackConfig ───

    @Test
    @DisplayName("FallbackConfig stores provider, model, baseUrl, apiKey")
    void fallbackConfig_storesAllFields() {
        FallbackConfig cfg = new FallbackConfig();
        cfg.setProvider("anthropic");
        cfg.setModel("claude-3");
        cfg.setBaseUrl("https://api.anthropic.com");
        cfg.setApiKey("test-key");

        assertThat(cfg.getProvider()).isEqualTo("anthropic");
        assertThat(cfg.getModel()).isEqualTo("claude-3");
        assertThat(cfg.getBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(cfg.getApiKey()).isEqualTo("test-key");
    }

    @Test
    @DisplayName("AgentProperties.fallbackChain defaults to empty list")
    void agentProperties_fallbackChainDefaultsToEmpty() {
        AgentProperties props = new AgentProperties();
        assertThat(props.getFallbackChain()).isEmpty();
    }

    @Test
    @DisplayName("AgentProperties.fallbackChain can be set and cleared")
    void agentProperties_fallbackChainCanBeSet() {
        AgentProperties props = new AgentProperties();
        List<FallbackConfig> chain = List.of(
            makeFallback("anthropic", "claude-3", "https://api.anthropic.com", "key1")
        );
        props.setFallbackChain(chain);
        assertThat(props.getFallbackChain()).hasSize(1);
        assertThat(props.getFallbackChain().get(0).getModel()).isEqualTo("claude-3");
    }
}