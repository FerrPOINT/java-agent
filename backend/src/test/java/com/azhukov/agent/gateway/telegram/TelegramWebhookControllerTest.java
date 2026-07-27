package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramWebhookControllerTest {

    @Test
    void receivesMessageAndDispatches() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().setAllowByDefault(true);
        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);
        TelegramWebhookController c = new TelegramWebhookController(routing, props);

        Map<String, Object> update = Map.of(
            "update_id", 1,
            "message", Map.of(
                "message_id", 1,
                "chat", Map.of("id", 754334329L),
                "from", Map.of("id", 123L, "username", "azhukov"),
                "text", "hello"
            )
        );
        String r = c.receive(update);
        assertThat(r).isEqualTo("OK");
        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].source().platform()).isEqualTo(Platform.TELEGRAM);
        assertThat(captured[0].text()).isEqualTo("hello");
    }

    @Test
    void returnsOkWhenNoMessage() {
        AgentProperties props = new AgentProperties();
        TelegramWebhookController c = new TelegramWebhookController(null, props);
        assertThat(c.receive(Map.of("update_id", 1))).isEqualTo("OK");
    }

    @Test
    void forbidsUnauthorizedUser() {
        AgentProperties props = new AgentProperties();
        props.getGateway().getTelegram().getAllowedUserIds().add("999");
        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);
        TelegramWebhookController c = new TelegramWebhookController(routing, props);

        Map<String, Object> update = Map.of(
            "message", Map.of(
                "chat", Map.of("id", 1L),
                "from", Map.of("id", 123L),
                "text", "hi"
            )
        );
        assertThat(c.receive(update)).isEqualTo("FORBIDDEN");
        assertThat(captured[0]).isNull();
    }
}
