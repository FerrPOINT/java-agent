package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramLongPollingServiceTest {

    @Test
    void processesUpdate() throws Exception {
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getGateway().getTelegram().setBotToken("dummy");

        CountDownLatch latch = new CountDownLatch(1);
        MessageEvent[] captured = new MessageEvent[1];
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> captured[0] = evt);

        var service = new TelegramLongPollingService(props, routing);
        Field restClientField = TelegramLongPollingService.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);

        // Stub RestClient via Mockito-like manual stub is heavy; use real local mock not possible.
        // Instead, test processUpdate through reflection.
        Map<String, Object> update = Map.of(
            "update_id", 42,
            "message", Map.of(
                "message_id", 1,
                "chat", Map.of("id", 754334329L, "type", "private"),
                "from", Map.of("id", 123L, "is_bot", false, "first_name", "Test"),
                "date", 1721900000,
                "text", "hello"
            )
        );
        var method = TelegramLongPollingService.class.getDeclaredMethod("processUpdate", Map.class);
        method.setAccessible(true);
        method.invoke(service, update);

        assertEquals(Platform.TELEGRAM, captured[0].source().platform());
        assertEquals("754334329", captured[0].source().chatId());
        assertEquals("hello", captured[0].text());
    }
}
