package com.azhukov.agent.bot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * The bot image deliberately owns only runtime mechanics. Product behavior
     * defaults stay in application.yml, so compose cannot silently drift from
     * the Spring configuration.
     */
    @Test
    void devComposeDoesNotOverrideToolProgressDefault() throws IOException {
        String compose = Files.readString(Path.of("..", "docker-compose.dev.yml"));

        assertThat(compose).doesNotContain("BOT_DISPLAY_TOOL_PROGRESS:");
    }

    @Test
    void defaultsToEditStreaming() {
        assertThat(properties.getStreamingTransport()).isEqualTo("edit");
    }

    @Test
    void defaultsToolProgressToAll() {
        assertThat(properties.getDisplay().getToolProgress()).isEqualTo("all");
    }

    @Test
    void bindsAgentName() {
        assertThat(properties.getAgentName()).isEqualTo("Джава агент");
    }
}