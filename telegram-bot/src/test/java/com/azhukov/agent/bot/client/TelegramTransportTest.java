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
    @Test
    void floodWaitFromOneChatDoesNotSuppressAnotherChatsRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot-fixture/sendMessage", exchange -> {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                requests.incrementAndGet();
                boolean firstChat = request.contains("101");
                String json = firstChat
                    ? """
                        {"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":17}}
                        """
                    : """
                        {"ok":true,"result":{"message_id":91}}
                        """;
                byte[] response = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(firstChat ? 429 : 200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var transport = new BotConfig().telegramRestClient(mapper).mutate()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var client = new TelegramClient(transport, mapper, "fixture", 0);
            assertThatThrownBy(() -> client.sendMessageChecked(101L, "first", null, null, null, null, false))
                .isInstanceOfSatisfying(TelegramClient.SendRefusalException.class,
                    error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(17));
            assertThat(client.sendMessageChecked(202L, "second", null, null, null, null, false)).contains(91L);
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

}
