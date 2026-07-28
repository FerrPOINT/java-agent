package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.bot.config.BotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TelegramClientConfig {

    @Bean
    public TelegramClient telegramClient(RestClient telegramRestClient,
                                          ObjectMapper objectMapper,
                                          BotProperties properties) {
        return new TelegramClient(telegramRestClient, objectMapper, properties.getToken());
    }
}