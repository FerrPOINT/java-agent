package com.azhukov.agent.cli;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;


/**
 * CLI configuration — provides RestClient and ObjectMapper beans.
 */
@EnableConfigurationProperties(CliProperties.class)
@Configuration
public class CliConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return SharedObjectMapper.get();
    }

    @Bean
    public MarkdownRenderer markdownRenderer() {
        return new MarkdownRenderer(true);
    }

    @Bean
    public ContextReferenceExpander contextReferenceExpander() {
        return new ContextReferenceExpander();
    }

    @Bean
    public RestClient backendRestClient(CliProperties properties) {
        // h10: shared factory (timeouts, X-API-Key, tolerant converters) —
        // same client behaviour as the telegram-bot module.
        return com.azhukov.agent.shared.http.BackendRestClientFactory.create(
            properties.getBackendUrl(), properties.getApiKey());
    }
}
