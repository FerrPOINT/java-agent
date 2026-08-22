package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.CodingContextDetector;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.prompt.DefaultPromptBuilder;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt-related beans: {@link PromptBuilder} and {@link PromptCacheTracker}.
 */
@Configuration
public class PromptConfig {

    @Bean
    @ConditionalOnMissingBean
    public PromptCacheTracker promptCacheTracker(AgentProperties properties) {
        return new PromptCacheTracker(properties);
    }

    @Bean
    public PromptBuilder promptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants agentConstants,
                                       PromptCacheTracker cacheTracker, CodingContextDetector codingContextDetector,
                                       MemoryProvider memoryProvider, SkillManager skillManager,
                                       com.azhukov.agent.core.context.CodingWorkspaceSnapshot codingWorkspaceSnapshot) {
        return new DefaultPromptBuilder(properties, toolRegistry, agentConstants, cacheTracker, codingContextDetector,
            memoryProvider, skillManager, codingWorkspaceSnapshot);
    }
}