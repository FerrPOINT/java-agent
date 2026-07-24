package com.azhukov.agent.config;

import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
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
import com.azhukov.agent.core.memory.NoOpMemoryProvider;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.sanitizer.DefaultMessageSanitizer;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.core.sanitizer.MessageSanitizer;
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
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.SkillRepository;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    public ModelClient openAiCompatibleModelClient(AgentProperties properties) {
        return new LangChain4jModelClient(properties, usage -> {});
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
                                     AgentProperties properties) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, toolExecutionService, promptBuilder, contextEngine,
            memoryProvider, skillManager, iterationBudget, messageSanitizer, contextReferenceService, properties);
    }

    @Bean
    public PromptBuilder promptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants agentConstants) {
        return new DefaultPromptBuilder(properties, toolRegistry, agentConstants);
    }

    @Bean
    public MessageSanitizer messageSanitizer() {
        return new DefaultMessageSanitizer();
    }

    @Bean
    public ContextEngine contextEngine(MemoryProvider memoryProvider,
                                       SkillManager skillManager,
                                       MessageRepository messageRepository,
                                       ContextCompressor contextCompressor,
                                       AgentProperties properties) {
        return new DefaultContextEngine(memoryProvider, skillManager, messageRepository, contextCompressor, properties);
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
    @ConditionalOnProperty(name = "agent.skills.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(SkillManager.class)
    public SkillManager skillManager(SkillRepository skillRepository) {
        return new DatabaseSkillManager(skillRepository);
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
    public ToolGuardrails toolGuardrails(AgentProperties properties) {
        return new DefaultToolGuardrails(properties);
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
    @ConditionalOnMissingBean(name = "gatewayMessageHandler")
    public java.util.function.Consumer<com.azhukov.agent.gateway.model.MessageEvent> gatewayMessageHandler() {
        return event -> { };
    }

    @Bean
    @ConditionalOnMissingBean(GatewayRoutingService.class)
    public GatewayRoutingService gatewayRoutingService(java.util.List<BasePlatformAdapter> adapters,
            java.util.function.Consumer<com.azhukov.agent.gateway.model.MessageEvent> gatewayMessageHandler) {
        return new GatewayRoutingService(adapters, gatewayMessageHandler);
    }
}