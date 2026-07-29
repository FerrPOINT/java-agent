package com.azhukov.agent.bot.config;

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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties(BotProperties.class)
@Slf4j
public class BotConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public RestClient telegramRestClient(ObjectMapper objectMapper) {
        // Telegram long-polling sometimes returns Content-Type: application/octet-stream
        // instead of application/json. We need a Jackson converter that also accepts
        // application/octet-stream so RestClient can deserialize the response.
        MappingJackson2HttpMessageConverter jacksonConverter =
            new MappingJackson2HttpMessageConverter(objectMapper);
        List<org.springframework.http.MediaType> supportedTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
        supportedTypes.add(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        jacksonConverter.setSupportedMediaTypes(supportedTypes);

        return RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                setReadTimeout((int) Duration.ofSeconds(60).toMillis());
            }})
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
                converters.add(jacksonConverter);
            })
            .build();
    }

    @Bean
    public RestClient backendRestClient(BotProperties properties) {
        return RestClient.builder()
            .baseUrl(properties.getBackendUrl())
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                setReadTimeout((int) Duration.ofMinutes(10).toMillis());
            }})
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
}