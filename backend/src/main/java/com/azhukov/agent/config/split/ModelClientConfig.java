package com.azhukov.agent.config.split;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.client.langchain4j.LangChain4jModelClient;
import com.azhukov.agent.client.langchain4j.RateLimitTracker;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.budget.DefaultIterationBudget;
import com.azhukov.agent.core.budget.IterationBudget;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.service.ImageShrinkerService;
import com.azhukov.agent.service.TurnUsageCollector;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Model-client related beans: the {@link ModelClient} (noop or OpenAI-compatible),
 * shared {@link ObjectMapper}, {@link IterationBudget}, and Resilience4j registries.
 */
@Configuration
public class ModelClientConfig {

    @Bean
    @ConditionalOnProperty(name = "agent.model.provider", havingValue = "noop")
    public ModelClient noopModelClient() {
        return new NoOpModelClient();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.model.provider", havingValue = "openai-compatible")
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient openAiCompatibleModelClient(AgentProperties properties, TurnUsageCollector turnUsageCollector,
                                                    ErrorClassifier errorClassifier, RateLimitTracker rateLimitTracker,
                                                    ImageShrinkerService imageShrinker) {
        return new LangChain4jModelClient(properties, usage -> {
            turnUsageCollector.record(usage.promptTokens(), usage.completionTokens());
        }, errorClassifier, rateLimitTracker, null, imageShrinker);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return SharedObjectMapper.get();
    }

    @Bean
    public IterationBudget iterationBudget(AgentProperties properties) {
        return new DefaultIterationBudget(properties);
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