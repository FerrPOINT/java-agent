package com.azhukov.agent.config;

import com.azhukov.agent.config.split.ContextConfig;
import com.azhukov.agent.config.split.MemoryConfig;
import com.azhukov.agent.config.split.ModelClientConfig;
import com.azhukov.agent.config.split.PromptConfig;
import com.azhukov.agent.config.split.AgentSecurityConfig;
import com.azhukov.agent.config.split.SessionConfig;
import com.azhukov.agent.config.split.SkillConfig;
import com.azhukov.agent.config.split.ToolConfig;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.CommentaryCallback;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.agent.TokenEstimator;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.agent.TurnFinalizer;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryManager;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.AgentState;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.state.DefaultAgentState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.security.ApprovalQueue;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UserInputSanitizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Aggregator {@code @Configuration} that ties together the domain-specific
 * split config classes via {@link Import}. Domain beans live in the
 * {@code com.azhukov.agent.config.split} subpackage:
 * <ul>
 *   <li>{@link ModelClientConfig} &mdash; model client, ObjectMapper, IterationBudget, Resilience4j registries</li>
 *   <li>{@link PromptConfig} &mdash; PromptBuilder, PromptCacheTracker</li>
 *   <li>{@link ContextConfig} &mdash; ContextCompressor, ContextReferenceService, ContextEngine</li>
 *   <li>{@link MemoryConfig} &mdash; MemoryProvider, MemoryThreatScanner, MemoryStore, WriteApprovalGate, BackgroundReviewService</li>
 *   <li>{@link SkillConfig} &mdash; SkillManager (database / noop)</li>
 *   <li>{@link SecurityConfig} &mdash; sanitizers, redactors, file/URL safety, guardrails, SSRF client</li>
 *   <li>{@link ToolConfig} &mdash; tool-related beans (registry/execution are component-scanned)</li>
 *   <li>{@link SessionConfig} &mdash; gateway message handler, routing service</li>
 * </ul>
 * This class retains the cross-cutting beans that don't belong to a single
 * domain: {@link AgentRuntime}, {@link TurnStateManager}, {@link AgentState},
 * {@link AgentConstants}, and {@link CommentaryCallback}.
 */
@Configuration
@Import({
    ModelClientConfig.class,
    PromptConfig.class,
    ContextConfig.class,
    MemoryConfig.class,
    SkillConfig.class,
    AgentSecurityConfig.class,
    ToolConfig.class,
    SessionConfig.class
})
public class AgentConfig {

    @Bean
    public AgentRuntime agentRuntime(ModelClient modelClient,
                                     ToolRegistry toolRegistry,
                                     ToolExecutionService toolExecutionService,
                                     PromptBuilder promptBuilder,
                                     ContextEngine contextEngine,
                                     MemoryProvider memoryProvider,
                                     SkillManager skillManager,
                                     IterationBudget iterationBudget,
                                     MessageSanitizer messageSanitizer,
                                     ContextReferenceService contextReferenceService,
                                     AgentProperties properties,
                                     UserInputSanitizer inputSanitizer,
                                     ToolCallGuardrail guardrail,
                                     TurnStateManager turnStateManager,
                                     BackgroundReviewService backgroundReviewService,
                                     InterruptToken interruptToken,
                                     TurnFinalizer turnFinalizer,
                                     SteerBuffer steerBuffer,
                                     ErrorClassifier errorClassifier,
                                     ContextCompressor contextCompressor,
                                     ApprovalQueue approvalQueue,
                                     MemoryManager memoryManager,
                                     TokenEstimator tokenEstimator,
                                     ToolResultFormatter toolResultFormatter,
                                     MidTurnPersistenceCallback midTurnPersistenceCallback,
                                     CommentaryCallback commentaryCallback) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, toolExecutionService, promptBuilder, contextEngine,
            memoryProvider, skillManager, iterationBudget, messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService, interruptToken, turnFinalizer, steerBuffer,
            errorClassifier, contextCompressor, approvalQueue, memoryManager, tokenEstimator, toolResultFormatter,
            midTurnPersistenceCallback, commentaryCallback);
    }

    @Bean
    @ConditionalOnMissingBean(TurnStateManager.class)
    public TurnStateManager turnStateManager() {
        return new TurnStateManager();
    }

    @Bean
    @ConditionalOnMissingBean(AgentState.class)
    public AgentState agentState() {
        return new DefaultAgentState();
    }

    @Bean
    @ConditionalOnMissingBean(AgentConstants.class)
    public AgentConstants agentConstants() {
        return new DefaultAgentConstants();
    }

    @Bean
    @ConditionalOnMissingBean(CommentaryCallback.class)
    public CommentaryCallback commentaryCallback() {
        return (sessionId, text, alreadyStreamed) -> {
            // No-op default for the REST API path. Commentary is handled via SSE
            // "commentary" events in the streaming path — the Telegram bot consumes
            // those events and issues a segment break. Non-streaming clients (REST
            // API) receive the commentary text as part of the regular response,
            // so no separate delivery is needed. Gateway implementations can
            // override this bean to send commentary as a separate message.
        };
    }
}