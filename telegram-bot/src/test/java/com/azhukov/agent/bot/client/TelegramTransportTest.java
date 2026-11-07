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
    void multipartArtifactKeepsForumTopicAndIsNotReplayed() throws Exception {
        var payloads = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botfixture/sendDocument", exchange -> {
            try (exchange) {
                payloads.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var transport = new BotConfig().telegramRestClient(mapper).mutate()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var client = new TelegramClient(transport, mapper, "fixture", 0);
            assertThat(client.sendDocument(101, new byte[] {1}, "report.pdf", null, null, 55)).isEmpty();
            assertThat(payloads).singleElement().asString()
                .contains("name=\"message_thread_id\"")
                .contains("\r\n\r\n55\r\n");
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
