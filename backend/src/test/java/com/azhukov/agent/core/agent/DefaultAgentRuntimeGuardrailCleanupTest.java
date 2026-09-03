package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.ToolGuardrails;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * rev-63: DefaultToolCallGuardrail.sessionStates was never cleaned up —
 * removeSession existed but had zero callers. cleanupSession must evict
 * guardrail per-session state like it does for every other collaborator.
 */
@ExtendWith(MockitoExtension.class)
class DefaultAgentRuntimeGuardrailCleanupTest {

    @Mock private ModelClient modelClient;
    @Mock private ToolRegistry toolRegistry;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private PromptBuilder promptBuilder;
    @Mock private ContextEngine contextEngine;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private com.azhukov.agent.core.budget.IterationBudget iterationBudget;
    @Mock private MessageSanitizer messageSanitizer;
    @Mock private com.azhukov.agent.core.context.ContextReferenceService contextReferenceService;
    @Mock private com.azhukov.agent.core.security.UserInputSanitizer inputSanitizer;
    @Mock private com.azhukov.agent.core.security.ToolCallGuardrail guardrail;
    @Mock private TurnStateManager turnStateManager;
    @Mock private com.azhukov.agent.core.memory.BackgroundReviewService backgroundReviewService;
    @Mock private InterruptToken interruptToken;
    @Mock private TurnFinalizer turnFinalizer;
    @Mock private SteerBuffer steerBuffer;
    @Mock private com.azhukov.agent.client.langchain4j.ErrorClassifier errorClassifier;
    @Mock private ContextCompressor contextCompressor;
    @Mock private ToolGuardrails toolGuardrails;

    @Test
    void cleanupSessionEvictsGuardrailState() {
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            modelClient, toolRegistry, toolExecutionService, promptBuilder,
            contextEngine, memoryProvider, skillManager, iterationBudget,
            messageSanitizer, contextReferenceService, new AgentProperties(),
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService,
            interruptToken, turnFinalizer, steerBuffer, errorClassifier, contextCompressor,
            new ApprovalQueue(), toolGuardrails, null,
            new TokenEstimator(), new ToolResultFormatter(), null, null);

        UUID sessionId = UUID.randomUUID();
        runtime.cleanupSession(sessionId);

        verify(toolGuardrails).removeSession(sessionId);
    }
}
