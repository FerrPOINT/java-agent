package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rev-55: webhook without secret must be fail-closed (GHSA-3vpc-7q5r-276h).
 * Previously the controller accepted any POST — forgeable updates.
 */
class TelegramWebhookFailClosedTest {

    @Test
    void missingSecretHeaderIsRejected() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().setWebhookSecret("my-secret");
        props.getGateway().getTelegram().setAllowByDefault(true);
        TelegramWebhookController c = new TelegramWebhookController(
            new GatewayRoutingService(List.of(), evt -> {}), props);
        ResponseEntity<String> r = c.receive(null, Map.of("update_id", 1));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void wrongSecretIsRejected() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().setWebhookSecret("my-secret");
        props.getGateway().getTelegram().setAllowByDefault(true);
        TelegramWebhookController c = new TelegramWebhookController(
            new GatewayRoutingService(List.of(), evt -> {}), props);
        ResponseEntity<String> r = c.receive("wrong", Map.of("update_id", 1));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void blankSecretInConfigRejectsAll() {
        AgentProperties props = new AgentProperties();
        // webhookSecret not set (default "")
        props.getGateway().getTelegram().setAllowByDefault(true);
        TelegramWebhookController c = new TelegramWebhookController(
            new GatewayRoutingService(List.of(), evt -> {}), props);
        ResponseEntity<String> r = c.receive("anything", Map.of("update_id", 1));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void correctSecretIsAccepted() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().setWebhookSecret("my-secret");
        props.getGateway().getTelegram().setAllowByDefault(true);
        TelegramWebhookController c = new TelegramWebhookController(
            new GatewayRoutingService(List.of(), evt -> {}), props);
        ResponseEntity<String> r = c.receive("my-secret", Map.of("update_id", 1));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }
}
