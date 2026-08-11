package com.azhukov.agent.config;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.langchain4j.RateLimitTracker;
import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.TurnFinalizer;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.context.DefaultContextReferenceService;
import com.azhukov.agent.core.memory.DatabaseMemoryProvider;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryStore;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import com.azhukov.agent.core.memory.NoOpMemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.state.TurnStateManager;
import com.azhukov.agent.security.CommandApprovalManager;
import com.azhukov.agent.security.DefaultToolCallGuardrail;
import com.azhukov.agent.security.FileSafetyValidator;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.SecretRedactor;
import com.azhukov.agent.security.SsrfSafeHttpClient;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.UrlSafetyHandler;
import com.azhukov.agent.security.UserInputSanitizer;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.InboundMessageProcessor;
import com.azhukov.agent.gateway.SessionResolver;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.AgentState;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.state.DefaultAgentState;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.security.DefaultToolGuardrails;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.ToolGuardrails;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.core.skill.DatabaseSkillManager;
import com.azhukov.agent.core.skill.NoOpSkillManager;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.repository.SkillRepository;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.TurnUsageCollector;
import com.azhukov.agent.tools.memory.MemoryTool;
import com.azhukov.agent.tools.memory.SkillManageTool;
import com.azhukov.agent.tools.memory.SkillViewTool;
import com.azhukov.agent.tools.memory.SkillsListTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AgentConfig {

    @Bean
    @ConditionalOnProperty(name = "agent.model.provider", havingValue = "noop")
    public ModelClient noopModelClient() {
        return new NoOpModelClient();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.model.provider", havingValue = "openai-compatible")
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient openAiCompatibleModelClient(AgentProperties properties, TurnUsageCollector turnUsageCollector,
                                                    ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker) {
        return new LangChain4jModelClient(properties, usage -> {
            turnUsageCollector.record(usage.promptTokens(), usage.completionTokens());
        }, errorClassifier, rateLimitTracker);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public IterationBudget iterationBudget(AgentProperties properties) {
        return new DefaultIterationBudget(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.azhukov.agent.core.prompt.PromptCacheTracker promptCacheTracker(AgentProperties properties) {
        return new com.azhukov.agent.core.prompt.PromptCacheTracker(properties);
    }

    @Bean
    public ContextCompressor contextCompressor(ModelClient modelClient, CompressionLockRepository lockRepository, AgentProperties properties) {
        return new DefaultContextCompressor(modelClient, lockRepository, properties);
    }

    @Bean
    public ContextReferenceService contextReferenceService(AgentProperties properties, SkillManager skillManager) {
        return new DefaultContextReferenceService(properties, skillManager);
    }

    @Bean
    public AgentRuntime agentRuntime(ModelClient modelClient,
                                     com.azhukov.agent.core.tool.ToolRegistry toolRegistry,
                                     com.azhukov.agent.core.tool.ToolExecutionService toolExecutionService,
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
                                     com.azhukov.agent.core.agent.SteerBuffer steerBuffer,
                                     ErrorClassifier errorClassifier,
                                     ContextCompressor contextCompressor,
                                     com.azhukov.agent.core.security.ApprovalQueue approvalQueue,
                                     com.azhukov.agent.core.memory.MemoryManager memoryManager) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, toolExecutionService, promptBuilder, contextEngine,
            memoryProvider, skillManager, iterationBudget, messageSanitizer, contextReferenceService, properties,
            inputSanitizer, guardrail, turnStateManager, backgroundReviewService, interruptToken, turnFinalizer, steerBuffer,
            errorClassifier, contextCompressor, approvalQueue, memoryManager);
    }

    @Bean
    public PromptBuilder promptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants agentConstants, com.azhukov.agent.core.prompt.PromptCacheTracker cacheTracker, com.azhukov.agent.core.context.CodingContextDetector codingContextDetector, MemoryProvider memoryProvider) {
        return new DefaultPromptBuilder(properties, toolRegistry, agentConstants, cacheTracker, codingContextDetector, memoryProvider);
    }

    @Bean
    public MessageSanitizer messageSanitizer(SecretRedactor redactor) {
        return new MessageSanitizer(redactor);
    }

    @Bean
    public UserInputSanitizer userInputSanitizer() {
        return new UserInputSanitizer();
    }

    @Bean
    public SecretRedactor secretRedactor(AgentProperties properties) {
        return new SecretRedactor(properties);
    }

    @Bean
    public FileSafetyValidator fileSafetyValidator(AgentProperties properties) {
        return new FileSafetyValidator(properties);
    }

    @Bean
    public UrlSafetyHandler urlSafetyHandler(AgentProperties properties, UrlSafety urlSafety) {
        return new UrlSafetyHandler(properties, urlSafety);
    }

    @Bean
    public SsrfSafeHttpClient ssrfSafeHttpClient(UrlSafetyHandler urlSafetyHandler, SecretRedactor redactor, AgentProperties properties) {
        return new SsrfSafeHttpClient(urlSafetyHandler, redactor, properties);
    }

    @Bean
    public CommandApprovalManager commandApprovalManager(AgentProperties properties) {
        return new CommandApprovalManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean(TurnStateManager.class)
    public TurnStateManager turnStateManager() {
        return new TurnStateManager();
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallGuardrail.class)
    public ToolCallGuardrail toolCallGuardrail(AgentProperties properties) {
        return new DefaultToolCallGuardrail(properties);
    }

    @Bean
    @ConditionalOnMissingBean(com.azhukov.agent.core.security.ToolGuardrails.class)
    public com.azhukov.agent.core.security.ToolGuardrails legacyToolGuardrails(AgentProperties properties,
                                                                                com.azhukov.agent.core.security.ApprovalQueue approvalQueue) {
        return new com.azhukov.agent.core.security.DefaultToolGuardrails(properties, approvalQueue);
    }

    @Bean
    public ContextEngine contextEngine(MemoryProvider memoryProvider,
                                       SkillManager skillManager,
                                       MessageRepository messageRepository,
                                       ContextCompressor contextCompressor,
                                       AgentProperties properties,
                                       com.azhukov.agent.core.prompt.PromptCacheTracker cacheTracker) {
        return new DefaultContextEngine(memoryProvider, skillManager, messageRepository, contextCompressor, properties, cacheTracker);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(MemoryProvider.class)
    public MemoryProvider memoryProvider(MemoryRepository memoryRepository) {
        return new DatabaseMemoryProvider(memoryRepository);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "false")
    @ConditionalOnMissingBean(MemoryProvider.class)
    public MemoryProvider noOpMemoryProvider() {
        return new NoOpMemoryProvider();
    }

    @Bean
    public MemoryThreatScanner memoryThreatScanner() {
        return new MemoryThreatScanner();
    }

    @Bean
    public MemoryStore memoryStore(MemoryThreatScanner threatScanner, AgentProperties properties) {
        return new MemoryStore(threatScanner, properties);
    }

    @Bean
    public WriteApprovalGate writeApprovalGate(PendingMemoryRepository pendingMemoryRepository,
                                                MemoryProvider memoryProvider,
                                                AgentProperties properties) {
        return new WriteApprovalGate(pendingMemoryRepository, memoryProvider, properties);
    }

    @Bean
    @ConditionalOnMissingBean(BackgroundReviewService.class)
    public BackgroundReviewService backgroundReviewService(ModelClient modelClient,
                                                            MemoryProvider memoryProvider,
                                                            WriteApprovalGate writeApprovalGate,
                                                            MemoryTool memoryTool,
                                                            SkillManageTool skillManageTool,
                                                            SkillsListTool skillsListTool,
                                                            SkillViewTool skillViewTool,
                                                            AgentProperties properties) {
        return new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate, memoryTool,
                                            skillManageTool, skillsListTool, skillViewTool, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.skills.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(SkillManager.class)
    public SkillManager skillManager(SkillRepository skillRepository, AgentProperties properties) {
        return new DatabaseSkillManager(skillRepository, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.skills.enabled", havingValue = "false")
    @ConditionalOnMissingBean(SkillManager.class)
    public SkillManager noOpSkillManager() {
        return new NoOpSkillManager();
    }

    @Bean
    @ConditionalOnMissingBean(FileSafety.class)
    public FileSafety fileSafety(AgentProperties properties) {
        return new DefaultFileSafety(properties);
    }

    @Bean
    @ConditionalOnMissingBean(UrlSafety.class)
    public UrlSafety urlSafety(AgentProperties properties) {
        return new DefaultUrlSafety(properties);
    }

    @Bean
    @ConditionalOnMissingBean(Redactor.class)
    public Redactor redactor(AgentProperties properties) {
        return new DefaultRedactor(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolGuardrails.class)
    public ToolGuardrails toolGuardrails(AgentProperties properties,
                                         com.azhukov.agent.core.security.ApprovalQueue approvalQueue) {
        return new DefaultToolGuardrails(properties, approvalQueue);
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

    @Bean(name = "gatewayMessageHandler")
    public java.util.function.Consumer<com.azhukov.agent.gateway.model.MessageEvent> gatewayMessageHandler(
            SessionResolver sessionResolver,
            AgentRuntime agentRuntime,
            org.springframework.beans.factory.ObjectProvider<GatewayRoutingService> routingServiceProvider,
            com.azhukov.agent.persistence.MessagePersistenceService messagePersistenceService) {
        return new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider, messagePersistenceService);
    }

    @Bean
    @ConditionalOnMissingBean(GatewayRoutingService.class)
    public GatewayRoutingService gatewayRoutingService(java.util.List<BasePlatformAdapter> adapters,
            java.util.function.Consumer<com.azhukov.agent.gateway.model.MessageEvent> gatewayMessageHandler) {
        return new GatewayRoutingService(adapters, gatewayMessageHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.ofDefaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }
}
