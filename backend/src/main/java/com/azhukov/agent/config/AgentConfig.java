package com.azhukov.agent.config;

import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.memory.DatabaseMemoryProvider;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.DatabaseSkillManager;
import com.azhukov.agent.core.skill.SkillManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
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
        return new LangChain4jModelClient(properties);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public AgentRuntime agentRuntime(ModelClient modelClient,
                                     com.azhukov.agent.core.tool.ToolRegistry toolRegistry,
                                     PromptBuilder promptBuilder,
                                     ContextEngine contextEngine,
                                     MemoryProvider memoryProvider,
                                     SkillManager skillManager,
                                     AgentProperties properties) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, promptBuilder, contextEngine,
            memoryProvider, skillManager, properties);
    }

    @Bean
    public PromptBuilder promptBuilder(AgentProperties properties) {
        return new DefaultPromptBuilder(properties);
    }

    @Bean
    public ContextEngine contextEngine() {
        return new DefaultContextEngine();
    }

    @Bean
    @Primary
    public MemoryProvider memoryProvider(DatabaseMemoryProvider databaseMemoryProvider) {
        return databaseMemoryProvider;
    }

    @Bean
    @Primary
    public SkillManager skillManager(DatabaseSkillManager databaseSkillManager) {
        return databaseSkillManager;
    }
}
