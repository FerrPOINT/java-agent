package com.azhukov.agent.gateway.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for TelegramLongPollingService NPE fix.
 * Verifies that missing chat/from fields don't cause NPE.
 */
class TelegramLongPollingServiceNpeTest {

    @Test
    void processUpdateWithNullChatDoesNotThrow() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        // Message with no "chat" field — should not NPE
        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "from", Map.of("id", 123L, "is_bot", false, "first_name", "Test"),
                "date", 1721900000,
                "text", "hello"
            )
        );

        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, update));

        // Should have dispatched with chatId=0
        if (captured[0] != null) {
            assertThat(captured[0].source().chatId()).isEqualTo("0");
        }
    }

    @Test
    void processUpdateWithNullFromDoesNotThrow() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        // Message with no "from" field — should not NPE
        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "chat", Map.of("id", 754334329L, "type", "private"),
                "date", 1721900000,
                "text", "hello"
            )
        );

        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, update));

        if (captured[0] != null) {
            assertThat(captured[0].source().userId()).isEqualTo("0");
        }
    }

    @Test
    void processUpdateWithNonNumberChatIdDoesNotThrow() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        // Message where chat id is a string instead of a number
        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "chat", Map.of("id", "not_a_number", "type", "private"),
                "from", Map.of("id", 123L, "is_bot", false, "first_name", "Test"),
                "date", 1721900000,
                "text", "hello"
            )
        );

        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);

        // Should not throw ClassCastException or NPE
        assertDoesNotThrow(() -> method.invoke(service, update));
    }

    @Test
    void processUpdateWithBothNullChatAndFromDoesNotThrow() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        // Message with neither chat nor from — should not NPE
        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "date", 1721900000,
                "text", "hello"
            )
        );

        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> method.invoke(service, update));
    }

    @Test
    void processUpdateWithValidDataStillWorks() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing, new ObjectMapper());

        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "chat", Map.of("id", 754334329L, "type", "private"),
                "from", Map.of("id", 123L, "is_bot", false, "first_name", "Test", "username", "tester"),
                "date", 1721900000,
                "text", "hello"
            )
        );

        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);
        method.invoke(service, update);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].source().platform()).isEqualTo(Platform.TELEGRAM);
        assertThat(captured[0].source().chatId()).isEqualTo("754334329");
        assertThat(captured[0].source().userId()).isEqualTo("123");
        assertThat(captured[0].text()).isEqualTo("hello");
    }
}