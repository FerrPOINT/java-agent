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
import com.azhukov.agent.core.ports.CompressionLockPort;
import com.azhukov.agent.core.ports.MessageStorePort;
import com.azhukov.agent.core.ports.SessionStorePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Context-related beans: {@link ContextCompressor}, {@link ContextReferenceService},
 * and {@link ContextEngine}.
 */
@Configuration(proxyBeanMethods = false)
public class ContextConfig {

    @Bean
    public ContextCompressor contextCompressor(ModelClient modelClient, CompressionLockPort lockRepository,
                                               AgentProperties properties, SessionStorePort sessionRepository,
                                               MessageStorePort messageRepository) {
        DefaultContextCompressor compressor = new DefaultContextCompressor(modelClient, lockRepository, properties);
        compressor.setSessionRepository(sessionRepository);
        compressor.setMessageRepository(messageRepository);
        return compressor;
    }

    @Bean
    public ContextReferenceService contextReferenceService(AgentProperties properties, SkillManager skillManager) {
        return new DefaultContextReferenceService(properties, skillManager);
    }

    @Bean
    public ContextEngine contextEngine(MemoryProvider memoryProvider,
                                       SkillManager skillManager,
                                       MessageStorePort messageRepository,
                                       ContextCompressor contextCompressor,
                                       AgentProperties properties,
                                       PromptCacheTracker cacheTracker,
                                       SessionLineagePort sessionLineageService,
                                       SessionStorePort sessionRepository,
                                       com.azhukov.agent.core.metadata.ModelMetadataService modelMetadataService) {
        // Perf fix (2026-08-28): WITHOUT modelMetadataService the engine's
        // contextLength stays 0 and shouldCompressPreflight falls back to the
        // 16K-token config max — a couple of bulky tool results tripped the
        // threshold on EVERY model call, causing a rotation storm (6+ child
        // sessions per turn, each with a summarizer LLM call + full history
        // rewrite). With metadata the threshold tracks the real model window.
        DefaultContextEngine engine = new DefaultContextEngine(memoryProvider, skillManager, messageRepository,
            contextCompressor, properties, cacheTracker, modelMetadataService);
        engine.setSessionLineageService(sessionLineageService);
        engine.setSessionRepository(sessionRepository);
        return engine;
    }
}