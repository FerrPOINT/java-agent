package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.*;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.UserInputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M17: Test that DefaultAgentRuntime uses a shared executor for parallel
 * tool execution instead of creating a new executor per tool batch.
 */
class DefaultAgentRuntimeSharedExecutorTest {

    private DefaultAgentRuntime runtime;
    private ModelClient modelClient;
    private ToolExecutionService toolExecutionService;
    private ToolRegistry toolRegistry;
    private IterationBudget iterationBudget;

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
        properties.getCore().setMaxTurns(1);
        properties.getError().setRetryAttempts(0);
        UserInputSanitizer inputSanitizer = mock(UserInputSanitizer.class);
        ToolCallGuardrail guardrail = mock(ToolCallGuardrail.class);
        TurnStateManager turnStateManager = mock(TurnStateManager.class);
        BackgroundReviewService backgroundReviewService = mock(BackgroundReviewService.class);
        InterruptToken interruptToken = mock(InterruptToken.class);
        TurnFinalizer turnFinalizer = mock(TurnFinalizer.class);
        SteerBuffer steerBuffer = mock(SteerBuffer.class);
        ErrorClassifier errorClassifier = new ErrorClassifier();

        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(any(String.class))).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("system"));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of(
                new ToolDefinition("test_tool", "test tool", java.util.Map.of())
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
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, null,
            new com.azhukov.agent.core.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    @Test
    void hasSharedParallelToolExecutorField() throws Exception {
        Field field = DefaultAgentRuntime.class.getDeclaredField("parallelToolExecutor");
        field.setAccessible(true);
        Object executor = field.get(runtime);
        assertThat(executor).isInstanceOf(ExecutorService.class);
        // Verify it's not shutdown (i.e., it's a shared, long-lived executor)
        assertThat(((ExecutorService) executor).isShutdown()).isFalse();
    }

    @Test
    void sharedExecutorIsNotClosedAfterToolExecution() throws Exception {
        // Execute a turn with tool calls
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "test_tool", "{}")
            )));
        when(toolExecutionService.execute(anyString(), anyString(), anyString(), any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("result"));

        runtime.run(List.of(Message.user("test")), List.of());

        // The shared executor should still be alive after execution
        Field field = DefaultAgentRuntime.class.getDeclaredField("parallelToolExecutor");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(runtime);
        assertThat(executor.isShutdown()).isFalse();
    }

    @Test
    void parallelToolExecutionUsesSameExecutorInstance() throws Exception {
        // Run two turns and verify the executor instance is the same (shared, not recreated)
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenReturn(ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "test_tool", "{}")
            )));
        when(toolExecutionService.execute(anyString(), anyString(), anyString(), any(), any(Session.class), any()))
            .thenReturn(ToolResult.ok("result"));

        runtime.run(List.of(Message.user("test1")), List.of());

        Field field = DefaultAgentRuntime.class.getDeclaredField("parallelToolExecutor");
        field.setAccessible(true);
        ExecutorService executor1 = (ExecutorService) field.get(runtime);

        runtime.run(List.of(Message.user("test2")), List.of());
        ExecutorService executor2 = (ExecutorService) field.get(runtime);

        // Same instance — shared executor, not recreated per batch
        assertThat(executor1).isSameAs(executor2);
        assertThat(executor1.isShutdown()).isFalse();
    }
}