package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryLedgerConsumerHttpTest {
    @Test
    void partialRefusalPostsUnknownThroughRealHttpClient() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/delivery/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var telegram = mock(TelegramClient.class);
            when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(Optional.of(42L)).thenThrow(new TelegramClient.SendRefusalException(30, "rate limited"));
            var rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var consumer = new DeliveryLedgerConsumer(rest, telegram, new BotProperties(), new ObjectMapper());
            var item = new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "source-1",
                "telegram", "123", "", "a".repeat(9000), 1);
            consumer.deliverBatchForTest(List.of(item));
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst()).startsWith("/api/v1/agent/delivery/unknown ")
                .contains("partial_chunk_failure", "id-1", "token-1");
            verify(telegram, times(2)).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean());
        } finally {
            server.stop(0);
        }
    }
}
