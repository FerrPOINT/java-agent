package com.azhukov.agent.gateway.telegram;

import java.util.Map;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramLongPollingServiceExtraTest {

    @Test
    void isRunningInitiallyFalse() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void startDoesNothingWhenTokenBlank() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("");
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.start();

        assertThat(service.isRunning()).isFalse();
        verifyNoInteractions(routing);
    }

    @Test
    void stopDoesNothingWhenNotStarted() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.stop();

        assertThat(service.isRunning()).isFalse();
    }


    @Test
    void processUpdateIgnoresNonMessage() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.processUpdate(Map.of("update_id", 1, "callback_query", Map.of()));

        verifyNoInteractions(routing);
    }

    @Test
    void processUpdateDispatchesMessage() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.processUpdate(Map.of(
            "update_id", 1,
            "message", Map.of(
                "from", Map.of("id", 42L, "username", "user"),
                "chat", Map.of("id", 100L),
                "text", "hello"
            )
        ));

        verify(routing).dispatchInbound(any());
    }
}
