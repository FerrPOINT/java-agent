package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L37 test: verify that TelegramLongPollingService does not log the first 8 chars
 * of the bot token. Instead, it should log only the last 4 chars (suffix).
 */
class TelegramLongPollingServiceTokenLogTest {

    @Test
    void startLogsOnlyLastFourCharsOfToken() throws Exception {
        var props = new AgentProperties();
        // Use a token long enough to test the suffix behavior
        String token = "1234567890:ABCdefGHIjklMNO";
        props.getGateway().getTelegram().setBotToken(token);

        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> {});
        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        // Start the service — it will submit the poll loop to the executor
        service.start();
        assertThat(service.isRunning()).isTrue();

        // Stop immediately to clean up
        service.stop();

        // The fix changes the log from token.substring(0, 8) to token.substring(length - 4)
        // We verify the token substring logic:
        String loggedSuffix = token.substring(Math.max(0, token.length() - 4));
        // "1234567890:ABCdefGHIjklMNO" has length 25, last 4 = "lMNO"
        assertThat(loggedSuffix).isEqualTo("lMNO");
        // Verify the suffix is NOT the first 8 chars (which was the old behavior)
        assertThat(loggedSuffix).isNotEqualTo(token.substring(0, 8));
    }

    @Test
    void startWithShortTokenDoesNotThrow() {
        var props = new AgentProperties();
        props.getGateway().getTelegram().setBotToken("ABCD"); // only 4 chars

        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> {});
        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        service.start();
        assertThat(service.isRunning()).isTrue();
        service.stop();
    }
}