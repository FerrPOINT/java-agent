package com.azhukov.agent.config;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.langchain4j.RateLimitTracker;
import com.azhukov.agent.client.NoOpModelClient;
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
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.*;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.AgentState;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.InboundMessageProcessor;
import com.azhukov.agent.gateway.SessionResolver;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.persistence.MessagePersistenceService;
import com.azhukov.agent.persistence.repository.*;
import com.azhukov.agent.security.*;
import com.azhukov.agent.service.TurnUsageCollector;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentConfigBeanMethodsTest {

    private final AgentConfig config = new AgentConfig();
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
        assertThat(config.noopModelClient()).isNotNull();
    }

    @Test
    void openAiCompatibleModelClient_bean() {
        properties.getModel().setProvider("openai-compatible");
        assertThat(config.openAiCompatibleModelClient(properties, mock(TurnUsageCollector.class), new ErrorClassifier(), new RateLimitTracker())).isInstanceOfAny(LangChain4jModelClient.class, ModelClient.class);
    }

    @Test
    void objectMapper_bean() {
        assertThat(config.objectMapper()).isNotNull();
    }

    @Test
    void iterationBudget_bean() {
        assertThat(config.iterationBudget(properties)).isNotNull();
    }

    @Test
    void contextCompressor_bean() {
        assertThat(config.contextCompressor(mock(ModelClient.class), mock(CompressionLockRepository.class), properties)).isNotNull();
    }

    @Test
    void contextReferenceService_bean() {
        assertThat(config.contextReferenceService(properties, mock(SkillManager.class))).isNotNull();
    }

    @Test
    void agentRuntime_bean() {
        assertThat(config.agentRuntime(mock(ModelClient.class), mock(ToolRegistry.class), mock(ToolExecutionService.class), mock(PromptBuilder.class), mock(ContextEngine.class), mock(MemoryProvider.class), mock(SkillManager.class), mock(IterationBudget.class), mock(MessageSanitizer.class), mock(ContextReferenceService.class), properties, mock(UserInputSanitizer.class), mock(ToolCallGuardrail.class), mock(TurnStateManager.class), mock(BackgroundReviewService.class), mock(InterruptToken.class), mock(TurnFinalizer.class), mock(com.azhukov.agent.core.agent.SteerBuffer.class))).isNotNull();
    }

    @Test
    void promptBuilder_bean() {
        assertThat(config.promptBuilder(properties, mock(ToolRegistry.class), mock(AgentConstants.class), mock(com.azhukov.agent.core.prompt.PromptCacheTracker.class), mock(com.azhukov.agent.core.context.CodingContextDetector.class))).isNotNull();
    }

    @Test
    void messageSanitizer_bean() {
        assertThat(config.messageSanitizer(mock(SecretRedactor.class))).isNotNull();
    }

    @Test
    void userInputSanitizer_bean() {
        assertThat(config.userInputSanitizer()).isNotNull();
    }

    @Test
    void secretRedactor_bean() {
        assertThat(config.secretRedactor(properties)).isNotNull();
    }

    @Test
    void fileSafetyValidator_bean() {
        assertThat(config.fileSafetyValidator(properties)).isNotNull();
    }

    @Test
    void urlSafetyHandler_bean() {
        assertThat(config.urlSafetyHandler(properties)).isNotNull();
    }

    @Test
    void ssrfSafeHttpClient_bean() {
        assertThat(config.ssrfSafeHttpClient(mock(UrlSafetyHandler.class), mock(SecretRedactor.class), properties)).isNotNull();
    }

    @Test
    void commandApprovalManager_bean() {
        assertThat(config.commandApprovalManager(properties)).isNotNull();
    }

    @Test
    void turnStateManager_bean() {
        assertThat(config.turnStateManager()).isNotNull();
    }

    @Test
    void toolCallGuardrail_bean() {
        assertThat(config.toolCallGuardrail(properties)).isNotNull();
    }

    @Test
    void legacyToolGuardrails_bean() {
        assertThat(config.legacyToolGuardrails(properties)).isNotNull();
    }

    @Test
    void contextEngine_bean() {
        assertThat(config.contextEngine(mock(MemoryProvider.class), mock(SkillManager.class), mock(MessageRepository.class), mock(ContextCompressor.class), properties, mock(com.azhukov.agent.core.prompt.PromptCacheTracker.class))).isNotNull();
    }

    @Test
    void memoryProvider_bean() {
        assertThat(config.memoryProvider(mock(MemoryRepository.class))).isNotNull();
    }

    @Test
    void noOpMemoryProvider_bean() {
        assertThat(config.noOpMemoryProvider()).isNotNull();
    }

    @Test
    void skillManager_bean() {
        assertThat(config.skillManager(mock(SkillRepository.class))).isNotNull();
    }

    @Test
    void noOpSkillManager_bean() {
        assertThat(config.noOpSkillManager()).isNotNull();
    }

    @Test
    void fileSafety_bean() {
        assertThat(config.fileSafety(properties)).isNotNull();
    }

    @Test
    void urlSafety_bean() {
        assertThat(config.urlSafety(properties)).isNotNull();
    }

    @Test
    void redactor_bean() {
        assertThat(config.redactor(properties)).isNotNull();
    }

    @Test
    void toolGuardrails_bean() {
        assertThat(config.toolGuardrails(properties)).isNotNull();
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
        assertThat(config.gatewayMessageHandler(mock(SessionResolver.class), mock(AgentRuntime.class), mock(ObjectProvider.class), mock(MessagePersistenceService.class))).isNotNull();
    }

    @Test
    void gatewayRoutingService_bean() {
assertThat(config.gatewayRoutingService(java.util.Collections.emptyList(), (java.util.function.Consumer<MessageEvent>) mock(java.util.function.Consumer.class))).isNotNull();
    }

    @Test
    void retryRegistry_bean() {
        assertThat(config.retryRegistry()).isNotNull();
    }

    @Test
    void timeLimiterRegistry_bean() {
        assertThat(config.timeLimiterRegistry()).isNotNull();
    }
}
