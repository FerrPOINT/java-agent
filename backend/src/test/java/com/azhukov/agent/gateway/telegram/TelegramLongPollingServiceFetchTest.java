package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramLongPollingServiceFetchTest {

    @Test
    void fetchUpdatesReturnsEmptyOnNon200() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("token");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TelegramLongPollingService service = new TelegramLongPollingService(properties, mock(GatewayRoutingService.class), new ObjectMapper(), client);
        List<Map<String, Object>> updates = service.fetchUpdates();
        assertThat(updates).isEmpty();
    }

    @Test
    void fetchUpdatesReturnsEmptyOnOkFalse() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("token");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":false,\"description\":\"bad\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TelegramLongPollingService service = new TelegramLongPollingService(properties, mock(GatewayRoutingService.class), new ObjectMapper(), client);
        List<Map<String, Object>> updates = service.fetchUpdates();
        assertThat(updates).isEmpty();
    }

    @Test
    void fetchUpdatesDecodesResultList() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("token");
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":true,\"result\":[{\"update_id\":1},{\"update_id\":2}]}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        TelegramLongPollingService service = new TelegramLongPollingService(properties, mock(GatewayRoutingService.class), new ObjectMapper(), client);
        List<Map<String, Object>> updates = service.fetchUpdates();
        assertThat(updates).hasSize(2);
    }

    @Test
    void fetchUpdatesHandlesException() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getGateway().getTelegram().setBotToken("token");
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new RuntimeException("boom"));

        TelegramLongPollingService service = new TelegramLongPollingService(properties, mock(GatewayRoutingService.class), new ObjectMapper(), client);
        List<Map<String, Object>> updates = service.fetchUpdates();
        assertThat(updates).isEmpty();
    }
}
