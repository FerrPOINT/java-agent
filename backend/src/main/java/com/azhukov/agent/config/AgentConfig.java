package com.azhukov.agent.config;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.DefaultAgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelClient modelClient() {
        return new NoOpModelClient();
    }

    @Bean
    public AgentRuntime agentRuntime(ModelClient modelClient, ToolRegistry toolRegistry,
                                     PromptBuilder promptBuilder, ContextEngine contextEngine,
                                     MemoryProvider memoryProvider, SkillManager skillManager,
                                     AgentProperties properties) {
        return new DefaultAgentRuntime(modelClient, toolRegistry, promptBuilder, contextEngine,
            memoryProvider, skillManager, properties);
    }
}
