package com.azhukov.agent.config;

import com.azhukov.agent.gateway.telegram.TelegramBotApiClient;
import com.azhukov.agent.gateway.telegram.TelegramRestClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class TelegramConfig {

    @Bean
    public TelegramBotApiClient telegramBotApiClient(AgentProperties properties) {
        var telegram = properties.getGateway().getTelegram();
        var timeout = Duration.ofSeconds(
            telegram.getTimeoutSeconds() > 0 ? telegram.getTimeoutSeconds() : 30
        );
        return new TelegramBotApiClient(telegram.getBotToken(), TelegramRestClientFactory.create(timeout));
    }
}
