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
@EnableConfigurationProperties(BackendProperties.class)
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
    public RestClient backendRestClient(BackendProperties properties, ObjectMapper objectMapper) {
        MappingJackson2HttpMessageConverter jacksonConverter =
            new MappingJackson2HttpMessageConverter(objectMapper);
        List<org.springframework.http.MediaType> supportedTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
        supportedTypes.add(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        jacksonConverter.setSupportedMediaTypes(supportedTypes);

        return RestClient.builder()
            .baseUrl(properties.getBackendUrl())
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                setReadTimeout((int) Duration.ofMinutes(10).toMillis());
            }})
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .build();
    }
}
