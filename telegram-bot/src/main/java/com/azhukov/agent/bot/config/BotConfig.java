package com.azhukov.agent.bot.config;

import com.azhukov.agent.bot.client.ContentTypeNormalizingInterceptor;
import com.azhukov.agent.bot.core.BotMessageProcessor;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.ReconnectWatcher;
import com.azhukov.agent.bot.webhook.WebhookSecretValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties(BotProperties.class)
@Slf4j
public class BotConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return SharedObjectMapper.get();
    }

    @Bean
    public RestClient telegramRestClient(ObjectMapper objectMapper) {
        // Telegram long-polling intermittently returns Content-Type: application/octet-stream
        // instead of application/json. The ContentTypeNormalizingInterceptor inspects the
        // response body and rewrites the Content-Type to application/json when the body is
        // valid JSON, so Spring's standard Jackson message converter handles deserialization.
        MappingJackson2HttpMessageConverter jacksonConverter =
            new MappingJackson2HttpMessageConverter(objectMapper);

        // Keep the canonical hostname for SNI and normal TLS peer verification.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());

        return RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(requestFactory)
            .messageConverters(converters -> {
                // TelegramResponse contains Jackson 2 JsonNode values.
                converters.removeIf(c -> c instanceof JacksonJsonHttpMessageConverter
                    || c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .requestInterceptor(new ContentTypeNormalizingInterceptor())
            .build();
    }

    @Bean
    public RestClient backendRestClient(BotProperties properties) {
        if (!properties.getToken().isBlank() && properties.getBackendApiKey().isBlank()) {
            throw new IllegalStateException("BOT_BACKEND_API_KEY is required when Telegram is enabled");
        }
        // Short-lived backend operations use the ordinary REST timeout.
        return com.azhukov.agent.shared.http.BackendRestClientFactory.create(
            properties.getBackendUrl(), properties.getBackendApiKey());
    }

    @Bean("streamingBackendRestClient")
    public RestClient streamingBackendRestClient(BotProperties properties) {
        return com.azhukov.agent.shared.http.BackendRestClientFactory.createStreaming(
            properties.getBackendUrl(), properties.getBackendApiKey());
    }

    @Bean
    public WebhookSecretValidator webhookSecretValidator(BotProperties properties) {
        return new WebhookSecretValidator(properties.getWebhook().getSecret());
    }

    @Bean
    public Consumer<UpdateEvent> updateHandler(BotMessageProcessor botMessageProcessor) {
        return botMessageProcessor::accept;
    }

    @Bean
    public com.azhukov.agent.bot.session.SessionResetPolicy sessionResetPolicy(BotProperties properties) {
        com.azhukov.agent.bot.session.SessionResetPolicy policy = new com.azhukov.agent.bot.session.SessionResetPolicy();
        BotProperties.SessionReset config = properties.getSessionReset();
        policy.setMode(com.azhukov.agent.bot.session.SessionResetMode.valueOf(config.getMode().toUpperCase()));
        policy.setAtHour(config.getAtHour());
        policy.setIdleMinutes(config.getIdleMinutes());
        policy.setNotify(config.isNotify());
        return policy;
    }
}