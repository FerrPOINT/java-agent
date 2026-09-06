package com.azhukov.agent.shared.http;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared factory for REST clients that talk to the backend API.
 * Consolidates the builder logic that telegram-bot and cli each carried
 * (timeouts, base URL, optional {@code X-API-Key} default header, tolerant
 * message converters backed by {@link com.azhukov.agent.shared.SharedObjectMapper}).
 *
 * <p>Module configuration classes call this factory inside their
 * {@code @Bean} methods; the factory itself is dependency-free so it can be
 * unit-tested without Spring.
 */
public final class BackendRestClientFactory {

    /** Default connect timeout for backend calls. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Default read timeout: one full agent turn can take minutes. */
    public static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    private BackendRestClientFactory() {
    }

    /**
     * Creates a {@link RestClient} for the backend API.
     *
     * @param baseUrl backend base URL (e.g. {@code http://localhost:8090})
     * @param apiKey  optional API key sent as {@code X-API-Key}; null/blank skips the header
     */
    public static RestClient create(String baseUrl, String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        RestClient.Builder builder = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
        }

        // Jackson converter with the shared mapper; also accept octet-stream
        // so error bodies without a JSON content type still deserialize.
        MappingJackson2HttpMessageConverter jacksonConverter =
            new MappingJackson2HttpMessageConverter(com.azhukov.agent.shared.SharedObjectMapper.get());
        List<MediaType> supportedTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
        supportedTypes.add(MediaType.APPLICATION_OCTET_STREAM);
        jacksonConverter.setSupportedMediaTypes(supportedTypes);

        return builder
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .build();
    }
}
