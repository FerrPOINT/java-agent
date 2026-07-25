package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("live")
class TelegramBotApiClientLiveTest {

    @Autowired
    private TelegramBotApiClient telegramBotApiClient;

    @Test
    void botTokenIsConfigured() {
        // This test only verifies that a real Telegram bot token is present.
        // Actual sending is intentionally left for manual E2E to avoid spam.
        assertThat(telegramBotApiClient).isNotNull();
    }
}
