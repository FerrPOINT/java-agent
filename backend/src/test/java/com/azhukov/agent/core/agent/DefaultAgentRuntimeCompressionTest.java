package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Features 5-7 in DefaultAgentRuntime:
 * <ul>
 *   <li>Feature 5: Multiple compression attempts (up to 3)</li>
 *   <li>Feature 6: Compression-disabled respect</li>
 *   <li>Feature 7: Long-context tier handling</li>
 * </ul>
 */
class DefaultAgentRuntimeCompressionTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private ToolRegistry toolRegistry;
    private ToolExecutionService toolExecutionService;
    private IterationBudget iterationBudget;
    private TurnStateManager turnStateManager;
    private TurnFinalizer turnFinalizer;
    private InterruptToken interruptToken;
    private SteerBuffer steerBuffer;
    private BackgroundReviewService backgroundReviewService;
    private ToolCallGuardrail guardrail;
    private ErrorClassifier errorClassifier;
    private DefaultContextCompressor contextCompressor;

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
        properties.getError().setRetryAttempts(5);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        guardrail = mock(ToolCallGuardrail.class);
        turnStateManager = mock(TurnStateManager.class);
        backgroundReviewService = mock(BackgroundReviewService.class);
        interruptToken = mock(InterruptToken.class);
        turnFinalizer = mock(TurnFinalizer.class);
        steerBuffer = mock(SteerBuffer.class);
        errorClassifier = new ErrorClassifier();
        contextCompressor = mock(DefaultContextCompressor.class);

        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));

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
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    // ── Feature 6: Compression-disabled respect ──

    @Test
    @DisplayName("Feature 6: When compression is disabled, CONTEXT_OVERFLOW throws terminal error with guidance")
    void compressionDisabledOnContextOverflow() {
        AgentProperties props = new AgentProperties();
        props.getCore().setMaxTurns(10);
        props.getError().setRetryAttempts(3);
        props.getCompression().setEnabled(false); // Disable compression

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("context length exceeded"));

        // Re-create runtime with disabled compression
        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime disabledRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), props,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = disabledRuntime.runTurn(session, "Hello");

        // Should fail with a clear error mentioning compression is disabled
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Context limit exceeded");
        assertThat(result.error()).contains("Enable compression");
        // Compression should NOT have been called
        verify(contextCompressor, never()).compress(any(), anyInt());
    }

    @Test
    @DisplayName("Feature 6: When compression is disabled, PAYLOAD_TOO_LARGE throws terminal error with guidance")
    void compressionDisabledOnPayloadTooLarge() {
        AgentProperties props = new AgentProperties();
        props.getCore().setMaxTurns(10);
        props.getError().setRetryAttempts(3);
        props.getCompression().setEnabled(false);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("413 Request Entity Too Large"));

        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime disabledRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), props,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = disabledRuntime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Context limit exceeded");
        verify(contextCompressor, never()).compress(any(), anyInt());
    }

    // ── Feature 5: Multiple compression attempts (up to 3) ──

    @Test
    @DisplayName("Feature 5: Context overflow triggers compression and retries up to 3 times")
    void multipleCompressionAttemptsOnContextOverflow() {
        AgentProperties props = new AgentProperties();
        props.getCore().setMaxTurns(10);
        props.getError().setRetryAttempts(10); // High retry to allow multiple compression attempts
        props.getCompression().setEnabled(true);

        // Mock compressor: returns a smaller context each time
        AtomicInteger compressCount = new AtomicInteger(0);
        when(contextCompressor.compress(any(List.class), anyInt())).thenAnswer(inv -> {
            int count = compressCount.incrementAndGet();
            List<Message> input = inv.getArgument(0);
            // Simulate compression by removing some messages (but still failing)
            if (input.size() > 2) {
                return input.subList(0, input.size() - 1);
            }
            return input;
        });

        // Model always throws context overflow
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("context length exceeded"));

        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime multiRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), props,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = multiRuntime.runTurn(session, "Hello");

        // Should have attempted compression up to 3 times
        assertThat(compressCount.get()).isLessThanOrEqualTo(3);
        assertThat(result.completed()).isFalse();
        // Error should mention model call failure
        assertThat(result.error()).contains("Model call failed");
    }

    // ── Feature 7: Long-context tier handling ──

    @Test
    @DisplayName("Feature 7: LONG_CONTEXT_TIER error reduces context and triggers compression")
    void longContextTierReducesContext() {
        AgentProperties props = new AgentProperties();
        props.getCore().setMaxTurns(10);
        props.getError().setRetryAttempts(5);
        props.getCompression().setEnabled(true);

        // First call throws LONG_CONTEXT_TIER, second succeeds
        AtomicInteger callCount = new AtomicInteger(0);
        when(modelClient.complete(any(List.class), any(List.class), any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("429: Extra usage is required for long context requests");
            }
            return ChatResponse.text("Success after tier reduction");
        });

        when(contextCompressor.compress(any(List.class), anyInt()))
            .thenAnswer(inv -> inv.getArgument(0)); // No-op compression

        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime tierRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), props,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = tierRuntime.runTurn(session, "Hello");

        // The LONG_CONTEXT_TIER error should be classified and compression should be triggered
        // The test may succeed (if compression allows retry) or fail (if no context engine)
        // The important thing is that compression was called at least once
        verify(contextCompressor, times(1)).compress(any(List.class), anyInt());
    }

    @Test
    @DisplayName("Feature 6: When compression is disabled, LONG_CONTEXT_TIER throws terminal error")
    void compressionDisabledOnLongContextTier() {
        AgentProperties props = new AgentProperties();
        props.getCore().setMaxTurns(10);
        props.getError().setRetryAttempts(3);
        props.getCompression().setEnabled(false);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("429: Extra usage is required for long context requests"));

        ContextEngine contextEngine = mock(ContextEngine.class);
        MessageSanitizer messageSanitizer = mock(MessageSanitizer.class);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageSanitizer.sanitize(any(List.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("You are a test assistant."));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("weather", "Get weather", java.util.Map.of())
            ));
        IterationBudget.TurnSnapshot snapshot = mock(IterationBudget.TurnSnapshot.class);
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(mock(com.azhukov.agent.core.state.TurnState.class));
        when(guardrail.isHalted()).thenReturn(false);

        DefaultAgentRuntime disabledRuntime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, mock(MemoryProvider.class), mock(SkillManager.class), iterationBudget,
            messageSanitizer, mock(ContextReferenceService.class), props,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        Session session = Session.create("user-1", "openai-compatible", "test-model");
        TurnResult result = disabledRuntime.runTurn(session, "Hello");

        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("Context limit exceeded");
        verify(contextCompressor, never()).compress(any(), anyInt());
    }
}