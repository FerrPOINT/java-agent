package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.service.GatewayConfigWriter;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramWebhookControllerAuthTest {

    @Test
    void allowsByUserId() {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().getAllowedUserIds().add("754334329");

        assertTrue(GatewayConfigWriter.isAllowed(props.getGateway().getTelegram(), "754334329", null));
        assertFalse(GatewayConfigWriter.isAllowed(props.getGateway().getTelegram(), "1", null));
    }

    @Test
    void allowsByUsername() {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().getAllowedUsernames().add("azhukov");

        assertTrue(GatewayConfigWriter.isAllowed(props.getGateway().getTelegram(), "0", "azhukov"));
        assertFalse(GatewayConfigWriter.isAllowed(props.getGateway().getTelegram(), "0", "other"));
    }

    @Test
    void allowByDefaultOverridesEmptyLists() {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setWebhookSecret("test-secret");
        props.getGateway().getTelegram().setAllowByDefault(true);

        assertTrue(GatewayConfigWriter.isAllowed(props.getGateway().getTelegram(), "0", null));
    }
}
