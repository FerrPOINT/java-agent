package com.azhukov.agent.bot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnableConfigurationProperties(BotProperties.class)
class BotPropertiesTest {

    @Autowired
    private BotProperties properties;

    @Test
    void bindsBasicProperties() {
        assertThat(properties.getToken()).isEqualTo("test-token");
        assertThat(properties.getMode()).isEqualTo("polling");
        assertThat(properties.getBackendUrl()).isEqualTo("http://localhost:9999");
    }

    @Test
    void bindsPollingDefaults() {
        assertThat(properties.getPolling().getTimeoutSeconds()).isEqualTo(30);
        assertThat(properties.getPolling().getLimit()).isEqualTo(100);
        assertThat(properties.getPolling().getReconnectDelayMs()).isEqualTo(5000);
    }

    @Test
    void bindsTypingInterval() {
        assertThat(properties.getTypingRefreshInterval()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void bindsAuthDefaults() {
        assertThat(properties.getAuth().isAllowByDefault()).isFalse();
        assertThat(properties.getAuth().getAllowedUserIds()).isEmpty();
    }

    @Test
    void bindsAgentName() {
        assertThat(properties.getAgentName()).isEqualTo("Джава агент");
    }
}