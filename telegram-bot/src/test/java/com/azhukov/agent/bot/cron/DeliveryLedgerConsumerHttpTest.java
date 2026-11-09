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
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void partialAckIsReportedWithoutRepeatingSend(org.springframework.boot.test.system.CapturedOutput output) {
        var builder = RestClient.builder().baseUrl("http://backend");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var ack = org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
            "http://backend/api/v1/agent/delivery/ack");
        server.expect(ack).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(ack).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        var telegram = mock(TelegramClient.class);
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        var consumer = new DeliveryLedgerConsumer(builder.build(), telegram, new BotProperties(), new ObjectMapper());
        var first = new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "first",
            "telegram", "123", "", "first", 1);
        var second = new DeliveryLedgerConsumer.ClaimedItem("id-2", "token-2", "cron_execution", "second",
            "telegram", "123", "", "second", 1);
        consumer.deliverBatchForTest(List.of(first, second));
        server.verify();
        verify(telegram).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean());
        assertThat(output.getAll()).contains("delivery_batch_ack_unconfirmed confirmed=1 items=2")
            .doesNotContain("delivery_batch_acked");
    }

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
                .thenReturn(Optional.of(42L)).thenThrow(new TelegramClient.SendRefusalException(429, 30, "rate limited"));
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
