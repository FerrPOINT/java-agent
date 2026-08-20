package com.azhukov.agent.bot.config;

import com.azhukov.agent.bot.client.ContentTypeNormalizingInterceptor;
import com.azhukov.agent.bot.client.DohIpDiscovery;
import com.azhukov.agent.bot.client.FallbackIpTransport;
import com.azhukov.agent.bot.client.TelegramRequestFactory;
import com.azhukov.agent.bot.core.BotMessageProcessor;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.ReconnectWatcher;
import com.azhukov.agent.bot.webhook.WebhookSecretValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
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

        // P0: Fallback IP Transport — discover fallback IPs via DoH and retry
        // against them when primary api.telegram.org is unreachable.
        // Wrapped in try-catch with short timeout: DoH discovery is a network call
        // that may fail in test/offline environments — degrade gracefully to seed IPs.
        FallbackIpTransport fallbackTransport;
        try {
            DohIpDiscovery dohDiscovery = new DohIpDiscovery();
            List<String> fallbackIps = dohDiscovery.discover();
            fallbackTransport = new FallbackIpTransport(fallbackIps);
        } catch (Exception e) {
            log.warn("DoH discovery failed during startup, using seed fallback IPs: {}", e.getMessage());
            fallbackTransport = new FallbackIpTransport(DohIpDiscovery.SEED_FALLBACK_IPS);
        }

        SimpleClientHttpRequestFactory requestFactory = new TelegramRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());

        return RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(requestFactory)
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .requestInterceptor(new ContentTypeNormalizingInterceptor())
            .requestInterceptor(fallbackTransport)
            .build();
    }

    @Bean
    public RestClient backendRestClient(BotProperties properties) {
        // l38: explicit factory bean instead of double-brace initialization
        // (anonymous subclass holds a hidden this$0 reference and leaks it).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofMinutes(10).toMillis());
        return RestClient.builder()
            .baseUrl(properties.getBackendUrl())
            .requestFactory(factory)
            .build();
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