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
}
