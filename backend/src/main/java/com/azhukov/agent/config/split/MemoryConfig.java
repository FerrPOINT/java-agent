package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.DatabaseMemoryProvider;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryStore;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import com.azhukov.agent.core.memory.NoOpMemoryProvider;
import com.azhukov.agent.core.memory.ReviewToolProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.ports.MemoryStorePort;
import com.azhukov.agent.core.ports.PendingMemoryStorePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Memory-related beans: {@link MemoryProvider} (database or noop),
 * {@link MemoryThreatScanner}, {@link MemoryStore}, {@link WriteApprovalGate},
 * and {@link BackgroundReviewService}.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryConfig {

    @Bean
    @ConditionalOnProperty(name = "agent.memory.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(MemoryProvider.class)
    public MemoryProvider memoryProvider(MemoryStorePort memoryRepository,
                                          AgentProperties agentProperties,
                                          MemoryThreatScanner memoryThreatScanner) {
        return new DatabaseMemoryProvider(memoryRepository, agentProperties, memoryThreatScanner);
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
    public WriteApprovalGate writeApprovalGate(PendingMemoryStorePort pendingMemoryRepository,
                                                MemoryProvider memoryProvider,
                                                AgentProperties properties) {
        return new WriteApprovalGate(pendingMemoryRepository, memoryProvider, properties);
    }

    @Bean
    @ConditionalOnMissingBean(BackgroundReviewService.class)
    public BackgroundReviewService backgroundReviewService(ModelClient modelClient,
                                                            MemoryProvider memoryProvider,
                                                            WriteApprovalGate writeApprovalGate,
                                                            ReviewToolProvider reviewToolProvider,
                                                            AgentProperties properties) {
        return new BackgroundReviewService(modelClient, memoryProvider, writeApprovalGate,
                                            reviewToolProvider, properties);
    }
}