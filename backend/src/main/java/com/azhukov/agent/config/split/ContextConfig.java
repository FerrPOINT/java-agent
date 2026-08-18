package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.context.ContextReferenceService;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.context.DefaultContextReferenceService;
import com.azhukov.agent.core.context.SessionLineagePort;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.CompressionLockRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Context-related beans: {@link ContextCompressor}, {@link ContextReferenceService},
 * and {@link ContextEngine}.
 */
@Configuration
public class ContextConfig {

    @Bean
    public ContextCompressor contextCompressor(ModelClient modelClient, CompressionLockRepository lockRepository,
                                               AgentProperties properties, SessionRepository sessionRepository) {
        DefaultContextCompressor compressor = new DefaultContextCompressor(modelClient, lockRepository, properties);
        compressor.setSessionRepository(sessionRepository);
        return compressor;
    }

    @Bean
    public ContextReferenceService contextReferenceService(AgentProperties properties, SkillManager skillManager) {
        return new DefaultContextReferenceService(properties, skillManager);
    }

    @Bean
    public ContextEngine contextEngine(MemoryProvider memoryProvider,
                                       SkillManager skillManager,
                                       MessageRepository messageRepository,
                                       ContextCompressor contextCompressor,
                                       AgentProperties properties,
                                       PromptCacheTracker cacheTracker,
                                       SessionLineagePort sessionLineageService) {
        DefaultContextEngine engine = new DefaultContextEngine(memoryProvider, skillManager, messageRepository,
            contextCompressor, properties, cacheTracker);
        engine.setSessionLineageService(sessionLineageService);
        return engine;
    }
}