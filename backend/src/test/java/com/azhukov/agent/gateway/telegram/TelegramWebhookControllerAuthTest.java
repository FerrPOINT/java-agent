package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramWebhookControllerAuthTest {

    @Test
    void allowsByUserId() {
        var props = new AgentProperties();
        props.getGateway().getTelegram().getAllowedUserIds().add("754334329");
        var controller = new TelegramWebhookController(null, props);

        assertTrue(controller.isAllowed(754334329L, null));
        assertFalse(controller.isAllowed(1L, null));
    }

    @Test
    void allowsByUsername() {
        var props = new AgentProperties();
        props.getGateway().getTelegram().getAllowedUsernames().add("azhukov");
        var controller = new TelegramWebhookController(null, props);

        assertTrue(controller.isAllowed(0L, "azhukov"));
        assertFalse(controller.isAllowed(0L, "other"));
    }

    @Test
    void allowByDefaultOverridesEmptyLists() {
        var props = new AgentProperties();
        props.getGateway().getTelegram().setAllowByDefault(true);
        var controller = new TelegramWebhookController(null, props);

        assertTrue(controller.isAllowed(0L, null));
    }
}
