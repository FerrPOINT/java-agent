package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    public RestClient backendRestClient(CliProperties properties, ObjectMapper objectMapper) {
        MappingJackson2HttpMessageConverter jacksonConverter =
            new MappingJackson2HttpMessageConverter(objectMapper);
        List<org.springframework.http.MediaType> supportedTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
        supportedTypes.add(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        jacksonConverter.setSupportedMediaTypes(supportedTypes);

        RestClient.Builder builder = RestClient.builder()
            .baseUrl(properties.getBackendUrl())
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                setReadTimeout((int) Duration.ofMinutes(10).toMillis());
            }});
        // Auth: send the API key when configured so the CLI works against a
        // backend with agent.security.api-key set (or with per-user keys).
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader("X-API-Key", properties.getApiKey());
        }
        return builder
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .build();
    }
}
