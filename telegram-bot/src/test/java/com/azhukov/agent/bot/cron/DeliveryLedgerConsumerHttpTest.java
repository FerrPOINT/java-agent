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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void batchStopsSendingWhenServerLeaseExpires(boolean expires) {
        var builder = RestClient.builder().baseUrl("http://backend");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var mapper = new ObjectMapper();
        String prefix = "http://backend/api/v1/agent/delivery/";
        var json = org.springframework.http.MediaType.APPLICATION_JSON;
        var item = mapper.createObjectNode().put("id", "id").put("claim_token", "token")
            .put("source_type", "cron_execution").put("source_id", "source")
            .put("platform", "telegram").put("chat_id", "123").put("payload", "x".repeat(6000));
        var claim = mapper.createObjectNode().put("claimed", true).put("lease_remaining_ms", 60_000);
        claim.putArray("items").add(item);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(prefix + "claim-batch-v2"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(claim.toString(), json));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(prefix + (expires ? "unknown" : "ack")))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(prefix + "claim-batch-v2"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("{\"claimed\":false}", json));
        var telegram = mock(TelegramClient.class);
        var time = new java.util.concurrent.atomic.AtomicLong();
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenAnswer(call -> {
                if (expires) time.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(1));
                return Optional.of(42L);
            });
        var properties = new BotProperties();
        properties.setCronDeliveryEnabled(true);
        new DeliveryLedgerConsumer(builder.build(), telegram, properties, mapper).consume(time::get);
        verify(telegram, times(expires ? 1 : 2))
            .sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", ",\"lease_remaining_ms\":\"1000\"", ",\"lease_remaining_ms\":-1"})
    void malformedLeaseStopsWithoutPostingAnOutcome(String leaseField) {
        var builder = RestClient.builder().baseUrl("http://backend");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "http://backend/api/v1/agent/delivery/claim-batch-v2"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"claimed\":true,\"items\":[{\"id\":\"item\",\"claim_token\":\"token\",\"platform\":\"telegram\",\"chat_id\":\"123\"}]" + leaseField + "}",
                org.springframework.http.MediaType.APPLICATION_JSON));
        var telegram = mock(TelegramClient.class);
        var properties = new BotProperties();
        properties.setCronDeliveryEnabled(true);
        new DeliveryLedgerConsumer(builder.build(), telegram, properties, new ObjectMapper()).consume();
        server.verify();
        verifyNoInteractions(telegram);
    }

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
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(Optional.of(42L));
        var consumer = new DeliveryLedgerConsumer(builder.build(), telegram, new BotProperties(), new ObjectMapper());
        var first = new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "first",
            "telegram", "123", "", "first", 1);
        var second = new DeliveryLedgerConsumer.ClaimedItem("id-2", "token-2", "cron_execution", "second",
            "telegram", "123", "", "second", 1);
        consumer.deliverBatchForTest(List.of(first, second));
        server.verify();
        verify(telegram).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
        assertThat(output.getAll()).contains("delivery_batch_ack_unconfirmed confirmed=1 items=2")
            .doesNotContain("delivery_batch_acked");
    }

    @Test
    void backendWithoutVersionedClaimNeverTriggersTelegramPost() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();
        try {
            var telegram = mock(TelegramClient.class);
            var rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var properties = new BotProperties();
            properties.setCronDeliveryEnabled(true);
            new DeliveryLedgerConsumer(rest, telegram, properties, new ObjectMapper()).consume();
            assertThat(requests).containsExactly("/api/v1/agent/delivery/claim-batch-v2");
            verifyNoInteractions(telegram);
        } finally {
            server.stop(0);
        }
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
            when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Optional.of(42L)).thenThrow(new TelegramClient.SendRefusalException(429, 30, "rate limited"));
            var rest = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var consumer = new DeliveryLedgerConsumer(rest, telegram, new BotProperties(), new ObjectMapper());
            var item = new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "source-1",
                "telegram", "123", "", "a".repeat(9000), 1);
            consumer.deliverBatchForTest(List.of(item));
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst()).startsWith("/api/v1/agent/delivery/unknown ")
                .contains("partial_chunk_failure", "id-1", "token-1");
            verify(telegram, times(2)).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
        } finally {
            server.stop(0);
        }
    }
}
