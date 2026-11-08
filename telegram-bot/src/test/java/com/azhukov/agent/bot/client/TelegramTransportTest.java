package com.azhukov.agent.bot.client;

import com.azhukov.agent.bot.config.BotConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramTransportTest {
    @Test
    void httpBadRequestWithTelegramEnvelopeIsDefiniteRefusal() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot-fixture/sendMessage", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = """
                    {"ok":false,"error_code":400,"description":"Bad Request"}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var transport = new BotConfig().telegramRestClient(mapper).mutate()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var client = new TelegramClient(transport, mapper, "-fixture", 0);
            assertThatThrownBy(() -> client.sendMessageChecked(1L, "fixture", null, null, null, null, false))
                .isInstanceOfSatisfying(TelegramClient.SendRefusalException.class,
                    refusal -> assertThat(refusal.getErrorCode()).isEqualTo(400));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rawConflictIsAmbiguousForSendAndRecognizedForPolling() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot-fixture/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] body = "upstream conflict".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(409, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var transport = new BotConfig().telegramRestClient(mapper).mutate()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var client = new TelegramClient(transport, mapper, "-fixture", 0);

            assertThat(client.sendMessageChecked(1L, "fixture", null, null, null, null, false)).isEmpty();
            assertThatThrownBy(() -> client.callApi("getUpdates", Map.of()))
                .isInstanceOfSatisfying(TelegramApiException.class,
                    conflict -> assertThat(conflict.getErrorCode()).isEqualTo(409));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lostReplyToAcceptedPostDoesNotTriggerAnotherRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot-fixture/sendMessage", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                // Simulate a connection lost after the platform consumed the POST.
            }
        });
        server.start();
        try {
            var transport = new BotConfig().telegramRestClient(new ObjectMapper()).mutate()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            assertThatThrownBy(() -> transport.post().uri("/bot-fixture/sendMessage")
                .body(Map.of("chat_id", 1, "text", "fixture")).retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
