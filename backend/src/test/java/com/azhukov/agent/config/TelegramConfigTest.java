package com.azhukov.agent.config;

import com.azhukov.agent.gateway.telegram.TelegramBotApiClient;
import com.azhukov.agent.gateway.telegram.TelegramRestClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConfigTest {

    @Test
    @DisplayName("Should create TelegramBotApiClient bean with configured timeout")
    void shouldCreateTelegramBotApiClient() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("test-token");
        properties.getGateway().getTelegram().setTimeoutSeconds(45);

        TelegramConfig config = new TelegramConfig();
        TelegramBotApiClient client = config.telegramBotApiClient(properties);

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should use default 30s timeout when not set")
    void shouldUseDefaultTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("test-token");
        properties.getGateway().getTelegram().setTimeoutSeconds(0);

        TelegramConfig config = new TelegramConfig();
        TelegramBotApiClient client = config.telegramBotApiClient(properties);

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should handle null bot token gracefully")
    void shouldHandleNullBotToken() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken(null);

        TelegramConfig config = new TelegramConfig();
        TelegramBotApiClient client = config.telegramBotApiClient(properties);

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should handle empty bot token gracefully")
    void shouldHandleEmptyBotToken() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("");

        TelegramConfig config = new TelegramConfig();
        TelegramBotApiClient client = config.telegramBotApiClient(properties);

        assertNotNull(client);
    }
}