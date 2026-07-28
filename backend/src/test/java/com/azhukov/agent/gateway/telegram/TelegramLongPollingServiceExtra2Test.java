package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramLongPollingServiceExtra2Test {

    @Test
    @DisplayName("start() does nothing when bot token is empty")
    void startDoesNothingWhenTokenEmpty() {
        AgentProperties properties = new AgentProperties();
        // Default token is ""
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.start();

        assertThat(service.isRunning()).isFalse();
        verifyNoInteractions(routing);
    }

    @Test
    @DisplayName("isRunning() returns false before start()")
    void isRunningReturnsFalseBeforeStart() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() sets running to false")
    void stopSetsRunningToFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("dummy-token");
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        service.stop();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("pollLoop processes updates correctly via fetchUpdates")
    void fetchUpdatesProcessesUpdatesCorrectly() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("dummy-token");
        GatewayRoutingService routing = mock(GatewayRoutingService.class);

        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"chat\":{\"id\":100},\"from\":{\"id\":200,\"username\":\"testuser\"},\"text\":\"hello\"}}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), client);
        List<Map<String, Object>> updates = service.fetchUpdates();

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).get("update_id")).isEqualTo(1);

        // Process the update
        service.processUpdate(updates.get(0));
        verify(routing).dispatchInbound(any());
    }

    @Test
    @DisplayName("processUpdate ignores non-message updates (callback_query only)")
    void processUpdateIgnoresNonMessageUpdates() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        // callback_query without message key
        service.processUpdate(Map.of(
            "update_id", 99,
            "callback_query", Map.of("id", "cb1", "data", "callback_data")
        ));

        verifyNoInteractions(routing);
    }

    @Test
    @DisplayName("processUpdate handles callback_query alongside message")
    void processUpdateHandlesCallbackQueryWithMessage() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        // Update with both callback_query and message — message should be processed
        service.processUpdate(Map.of(
            "update_id", 55,
            "callback_query", Map.of("id", "cb1", "data", "callback_data"),
            "message", Map.of(
                "from", Map.of("id", 42L, "username", "user"),
                "chat", Map.of("id", 100L),
                "text", "callback test"
            )
        ));

        verify(routing).dispatchInbound(any());
    }

    @Test
    @DisplayName("processUpdate with null message returns without dispatching")
    void processUpdateWithNullMessageReturns() {
        AgentProperties properties = new AgentProperties();
        GatewayRoutingService routing = mock(GatewayRoutingService.class);
        TelegramLongPollingService service = new TelegramLongPollingService(properties, routing, new ObjectMapper(), HttpClient.newHttpClient());

        // update_id only, no message key
        service.processUpdate(Map.of("update_id", 77));

        verifyNoInteractions(routing);
    }
}