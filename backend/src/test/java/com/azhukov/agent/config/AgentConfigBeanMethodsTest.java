package com.azhukov.agent.config;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.langchain4j.RateLimitTracker;
import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.split.ContextConfig;
import com.azhukov.agent.config.split.MemoryConfig;
import com.azhukov.agent.config.split.ModelClientConfig;
import com.azhukov.agent.config.split.PromptConfig;
import com.azhukov.agent.config.split.AgentSecurityConfig;
import com.azhukov.agent.config.split.SessionConfig;
import com.azhukov.agent.config.split.SkillConfig;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.TurnFinalizer;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.*;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.AgentState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.SessionResolver;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.persistence.MessagePersistenceService;
import com.azhukov.agent.persistence.repository.*;
import com.azhukov.agent.service.ImageShrinker;
import com.azhukov.agent.service.TurnUsageCollector;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that every @Bean method previously defined in AgentConfig still
 * produces a non-null bean. Methods have been moved into domain-specific
 * split config classes; this test instantiates each split config directly.
 */
class AgentConfigBeanMethodsTest {

    private final AgentConfig config = new AgentConfig();
    private final ModelClientConfig modelClientConfig = new ModelClientConfig();
    private final PromptConfig promptConfig = new PromptConfig();
    private final ContextConfig contextConfig = new ContextConfig();
    private final MemoryConfig memoryConfig = new MemoryConfig();
    private final SkillConfig skillConfig = new SkillConfig();
    private final AgentSecurityConfig securityConfig = new AgentSecurityConfig();
    private final SessionConfig sessionConfig = new SessionConfig();
    private final AgentProperties properties = initProperties();

    private static AgentProperties initProperties() {
        AgentProperties p = new AgentProperties();
        p.getModel().setApiKey("sk-dummy");
        p.getModel().setBaseUrl("http://localhost:8080");
        p.getModel().setModelName("dummy");
        p.getModel().setTimeoutSeconds(30);
        p.getCore().setHttpClientTimeoutSeconds(10);
        p.getCore().setHttpUserAgent("test-agent");
        return p;
    }

        @Test
    void noopModelClient_bean() {
        assertThat(modelClientConfig.noopModelClient()).isNotNull();
    }

    @Test
    void openAiCompatibleModelClient_bean() {
        properties.getModel().setProvider("openai-compatible");
        assertThat(modelClientConfig.openAiCompatibleModelClient(properties, mock(TurnUsageCollector.class), new ErrorClassifier(), new RateLimitTracker(), new ImageShrinker(properties))).isInstanceOfAny(LangChain4jModelClient.class, ModelClient.class);
    }

    @Test
    void objectMapper_bean() {
        assertThat(modelClientConfig.objectMapper()).isNotNull();
    }

    @Test
    void iterationBudget_bean() {
        assertThat(modelClientConfig.iterationBudget(properties)).isNotNull();
    }

    @Test
    void contextCompressor_bean() {
        assertThat(contextConfig.contextCompressor(mock(ModelClient.class), mock(CompressionLockRepository.class), properties, mock(SessionRepository.class))).isNotNull();
    }

    @Test
    void contextReferenceService_bean() {
        assertThat(contextConfig.contextReferenceService(properties, mock(SkillManager.class))).isNotNull();
    }

    @Test
    void agentRuntime_bean() {
        assertThat(config.agentRuntime(mock(ModelClient.class), mock(ToolRegistry.class), mock(ToolExecutionService.class), mock(PromptBuilder.class), mock(ContextEngine.class), mock(MemoryProvider.class), mock(SkillManager.class), mock(IterationBudget.class), mock(MessageSanitizer.class), mock(ContextReferenceService.class), properties, mock(UserInputSanitizer.class), mock(ToolCallGuardrail.class), mock(TurnStateManager.class), mock(BackgroundReviewService.class), mock(InterruptToken.class), mock(TurnFinalizer.class), mock(com.azhukov.agent.core.agent.SteerBuffer.class), mock(ErrorClassifier.class), mock(ContextCompressor.class), mock(com.azhukov.agent.core.security.ApprovalQueue.class), mock(com.azhukov.agent.core.memory.MemoryManager.class), mock(com.azhukov.agent.core.agent.TokenEstimator.class), mock(com.azhukov.agent.core.agent.ToolResultFormatter.class), mock(com.azhukov.agent.core.agent.MidTurnPersistenceCallback.class), mock(com.azhukov.agent.core.agent.CommentaryCallback.class))).isNotNull();
    }

    @Test
    void promptBuilder_bean() {
        assertThat(promptConfig.promptBuilder(properties, mock(ToolRegistry.class), mock(AgentConstants.class), mock(com.azhukov.agent.core.prompt.PromptCacheTracker.class), mock(com.azhukov.agent.core.context.CodingContextDetector.class), mock(MemoryProvider.class), mock(com.azhukov.agent.core.skill.SkillManager.class))).isNotNull();
    }

    @Test
    void messageSanitizer_bean() {
        assertThat(securityConfig.messageSanitizer(mock(SecretRedactor.class))).isNotNull();
    }

    @Test
    void userInputSanitizer_bean() {
        assertThat(securityConfig.userInputSanitizer()).isNotNull();
    }

    @Test
    void secretRedactor_bean() {
        assertThat(securityConfig.secretRedactor(properties)).isNotNull();
    }

    @Test
    void fileSafetyValidator_bean() {
        assertThat(securityConfig.fileSafetyValidator(properties)).isNotNull();
    }

    @Test
    void urlSafetyHandler_bean() {
        assertThat(securityConfig.urlSafetyHandler(properties, new DefaultUrlSafety(properties))).isNotNull();
    }

    @Test
    void ssrfSafeHttpClient_bean() {
        assertThat(securityConfig.ssrfSafeHttpClient(mock(UrlSafetyHandler.class), mock(SecretRedactor.class), properties)).isNotNull();
    }

    @Test
    void commandApprovalManager_bean() {
        assertThat(securityConfig.commandApprovalManager(properties)).isNotNull();
    }

    @Test
    void turnStateManager_bean() {
        assertThat(config.turnStateManager()).isNotNull();
    }

    @Test
    void toolCallGuardrail_bean() {
        assertThat(securityConfig.toolCallGuardrail(properties)).isNotNull();
    }

    @Test
    void toolGuardrails_bean() {
        assertThat(securityConfig.toolGuardrails(properties, mock(com.azhukov.agent.core.security.ApprovalQueue.class))).isNotNull();
    }

    @Test
    void contextEngine_bean() {
        assertThat(contextConfig.contextEngine(mock(MemoryProvider.class), mock(SkillManager.class), mock(MessageRepository.class), mock(ContextCompressor.class), properties, mock(com.azhukov.agent.core.prompt.PromptCacheTracker.class), mock(com.azhukov.agent.core.context.SessionLineagePort.class), mock(com.azhukov.agent.persistence.repository.SessionRepository.class))).isNotNull();
    }

    @Test
    void memoryProvider_bean() {
        assertThat(memoryConfig.memoryProvider(mock(MemoryRepository.class), new AgentProperties(), new com.azhukov.agent.core.memory.MemoryThreatScanner())).isNotNull();
    }

    @Test
    void noOpMemoryProvider_bean() {
        assertThat(memoryConfig.noOpMemoryProvider()).isNotNull();
    }

    @Test
    void skillManager_bean() {
        assertThat(skillConfig.skillManager(mock(SkillRepository.class), new AgentProperties())).isNotNull();
    }

    @Test
    void noOpSkillManager_bean() {
        assertThat(skillConfig.noOpSkillManager()).isNotNull();
    }

    @Test
    void fileSafety_bean() {
        assertThat(securityConfig.fileSafety(properties)).isNotNull();
    }

    @Test
    void urlSafety_bean() {
        assertThat(securityConfig.urlSafety(properties)).isNotNull();
    }

    @Test
    void redactor_bean() {
        assertThat(securityConfig.redactor(properties)).isNotNull();
    }

    @Test
    void agentState_bean() {
        assertThat(config.agentState()).isNotNull();
    }

    @Test
    void agentConstants_bean() {
        assertThat(config.agentConstants()).isNotNull();
    }

    @Test
    void gatewayMessageHandler_bean() {
        assertThat(sessionConfig.gatewayMessageHandler(mock(SessionResolver.class), mock(AgentRuntime.class), mock(ObjectProvider.class), mock(MessagePersistenceService.class), mock(com.azhukov.agent.core.agent.MidTurnPersistenceCallback.class), properties, mock(com.azhukov.agent.core.agent.SteerBuffer.class))).isNotNull();
    }

    @Test
    void gatewayRoutingService_bean() {
assertThat(sessionConfig.gatewayRoutingService(java.util.Collections.emptyList(), (java.util.function.Consumer<MessageEvent>) mock(java.util.function.Consumer.class))).isNotNull();
    }

    @Test
    void retryRegistry_bean() {
        assertThat(modelClientConfig.retryRegistry()).isNotNull();
    }

    @Test
    void timeLimiterRegistry_bean() {
        assertThat(modelClientConfig.timeLimiterRegistry()).isNotNull();
    }
}