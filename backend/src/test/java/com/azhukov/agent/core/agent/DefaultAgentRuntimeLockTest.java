package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for the per-session ReentrantLock in DefaultAgentRuntime.
 * Verifies that concurrent turns on the same session are serialized,
 * turns on different sessions run in parallel, and locks are released on exception.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultAgentRuntimeLockTest {

    @Mock
    private ModelClient modelClient;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private ContextEngine contextEngine;
    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private IterationBudget iterationBudget;
    @Mock
    private MessageSanitizer messageSanitizer;
    @Mock
    private ContextReferenceService contextReferenceService;
    @Mock
    private UserInputSanitizer inputSanitizer;
    @Mock
    private ToolCallGuardrail guardrail;
    @Mock
    private TurnStateManager turnStateManager;
    @Mock
    private BackgroundReviewService backgroundReviewService;
    @Mock
    private InterruptToken interruptToken;
    @Mock
    private TurnFinalizer turnFinalizer;
    @Mock
    private SteerBuffer steerBuffer;

    private AgentProperties properties;
    private IterationBudget.TurnSnapshot snapshot;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setMaxTurns(1);
        properties.getError().setRetryAttempts(0);

        snapshot = org.mockito.Mockito.mock(IterationBudget.TurnSnapshot.class);

        when(messageSanitizer.sanitize(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inputSanitizer.sanitize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(promptBuilder.buildSystemMessage(any(Session.class)))
            .thenReturn(Message.system("system"));
        when(contextEngine.prepareContext(any(Session.class), any(List.class)))
            .thenAnswer(inv -> inv.getArgument(1));
        when(toolRegistry.getDefinitions(anySet()))
            .thenReturn(List.<ToolDefinition>of());
        when(iterationBudget.startTurn(any(UUID.class))).thenReturn(snapshot);
        when(iterationBudget.isExhausted(any())).thenReturn(false);
        when(iterationBudget.recordModelCall(any(), any(int.class), any(int.class))).thenReturn(snapshot);
        when(turnStateManager.getOrStart(any(UUID.class), any(int.class)))
            .thenReturn(org.mockito.Mockito.mock(com.azhukov.agent.core.state.TurnState.class));
        // Use any() matcher to cover both isHalted() and isHalted(UUID)
        when(guardrail.isHalted(any())).thenReturn(false);
    }

    private DefaultAgentRuntime buildRuntime() {
        return new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, new ErrorClassifier(), null,
            new com.azhukov.agent.security.ApprovalQueue(), null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);
    }

    @Test
    void concurrentTurnOnSameSessionSerializes() throws Exception {
        // Two threads call runTurn on the same session; verify they are serialized
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int cur = concurrentCount.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                Thread.sleep(200); // hold the lock for a bit
                concurrentCount.decrementAndGet();
                return ChatResponse.text("done");
            });

        DefaultAgentRuntime runtime = buildRuntime();
        Session session = Session.create("user", "noop", "model");

        var latch = new CountDownLatch(2);
        var results = new TurnResult[2];
        var threads = new Thread[2];

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                results[idx] = runtime.runTurn(session, "hello", List.of(), ModelRequestOptions.empty());
                latch.countDown();
            });
        }

        threads[0].start();
        // Give first thread time to acquire the lock
        Thread.sleep(50);
        threads[1].start();
        latch.await(15, TimeUnit.SECONDS);

        // Since turns are serialized, maxConcurrent should be 1
        assertThat(maxConcurrent.get()).isEqualTo(1);
        // Both should complete successfully
        assertThat(results[0]).isNotNull();
        assertThat(results[1]).isNotNull();
        assertThat(results[0].completed()).isTrue();
        assertThat(results[1].completed()).isTrue();
    }

    @Test
    void concurrentTurnOnDifferentSessionsRunsInParallel() throws Exception {
        // Two threads on different sessions; verify they can run in parallel
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch bothStarted = new CountDownLatch(2);

        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenAnswer(inv -> {
                int cur = concurrentCount.incrementAndGet();
                maxConcurrent.accumulateAndGet(cur, Math::max);
                bothStarted.countDown();
                Thread.sleep(300); // hold to allow overlap
                concurrentCount.decrementAndGet();
                return ChatResponse.text("done");
            });

        DefaultAgentRuntime runtime = buildRuntime();
        Session session1 = Session.create("user1", "noop", "model");
        Session session2 = Session.create("user2", "noop", "model");

        var latch = new CountDownLatch(2);
        var results = new TurnResult[2];

        Thread t1 = new Thread(() -> {
            results[0] = runtime.runTurn(session1, "hello", List.of(), ModelRequestOptions.empty());
            latch.countDown();
        });
        Thread t2 = new Thread(() -> {
            results[1] = runtime.runTurn(session2, "world", List.of(), ModelRequestOptions.empty());
            latch.countDown();
        });

        t1.start();
        t2.start();
        latch.await(15, TimeUnit.SECONDS);

        // Different sessions should allow parallel execution
        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
        assertThat(results[0].completed()).isTrue();
        assertThat(results[1].completed()).isTrue();
    }

    @Test
    void turnLockReleasedOnException() {
        // When runTurnInternal throws, the lock should be released.
        // We verify by ensuring the next call does not deadlock.

        // First call throws, second call succeeds
        when(modelClient.complete(any(List.class), any(List.class), any()))
            .thenThrow(new RuntimeException("model crashed"))
            .thenReturn(ChatResponse.text("recovered"));

        DefaultAgentRuntime runtime = buildRuntime();
        Session session = Session.create("user", "noop", "model");

        // First call should fail (but release the lock)
        TurnResult firstResult = runtime.runTurn(session, "hello", List.of(), ModelRequestOptions.empty());
        assertThat(firstResult.completed()).isFalse();
        assertThat(firstResult.error()).contains("Model call failed");

        // Second call should succeed (proving the lock was released)
        TurnResult secondResult = runtime.runTurn(session, "hello again", List.of(), ModelRequestOptions.empty());
        assertThat(secondResult.completed()).isTrue();
    }
}