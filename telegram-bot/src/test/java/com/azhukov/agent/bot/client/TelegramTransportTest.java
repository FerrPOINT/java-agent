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
    void sendGuardIsCheckedAfterWaitingForRateLimitPermit() throws Exception {
        var requests = new AtomicInteger();
        var mapper = new ObjectMapper();
        var transport = new BotConfig().telegramRestClient(mapper).mutate()
            .requestInterceptor((request, bytes, execution) -> {
                requests.incrementAndGet();
                return new org.springframework.mock.http.client.MockClientHttpResponse(
                    new byte[0], org.springframework.http.HttpStatus.OK);
            }).build();
        var client = new TelegramClient(transport, mapper, "fixture", 1);
        var permit = org.mockito.Mockito.mock(io.github.resilience4j.ratelimiter.RateLimiter.class);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(permit.acquirePermission()).thenAnswer(call -> {
            waiting.countDown();
            return release.await(2, java.util.concurrent.TimeUnit.SECONDS);
        });
        org.springframework.test.util.ReflectionTestUtils.setField(client, "rateLimiter", permit);
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var send = new java.util.concurrent.FutureTask<>(() ->
            client.sendMessageChecked(1L, "fixture", null, null, null, null, false, allowed::get));
        try {
            Thread.startVirtualThread(send);
            assertThat(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            allowed.set(false);
        } finally {
            release.countDown();
        }
        assertThat(send.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {200, 429})
    void multipartSendPreservesConfirmedFloodControl(int status) {
        var mapper = new ObjectMapper();
        var transport = new BotConfig().telegramRestClient(mapper).mutate()
            .requestInterceptor((request, bytes, execution) -> {
                String body = """
                    {"ok":false,"error_code":429,"description":"Flood control","parameters":{"retry_after":17}}
                    """;
                var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), org.springframework.http.HttpStatus.valueOf(status));
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                return response;
            }).build();
        var client = new TelegramClient(transport, mapper, "fixture", 0);
        assertThatThrownBy(() -> client.sendDocument(1L, new byte[] {1}, "report.txt", null, null))
            .isInstanceOfSatisfying(TelegramClient.SendRefusalException.class, refusal -> {
                assertThat(refusal.getErrorCode()).isEqualTo(429);
                assertThat(refusal.getRetryAfterSeconds()).isEqualTo(17);
            });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Proxy rate limit", "{\"error_code\":429}"})
    void proxyFloodControlWithoutTelegramEnvelopeHasUnknownOutcome(String body) {
        var mapper = new ObjectMapper();
        var transport = new BotConfig().telegramRestClient(mapper).mutate()
            .requestInterceptor((request, bytes, execution) ->
                new org.springframework.mock.http.client.MockClientHttpResponse(
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS))
            .build();
        var client = new TelegramClient(transport, mapper, "fixture", 0);
        assertThat(client.sendDocument(1L, new byte[] {1}, "report.txt", null, null)).isEmpty();
    }

    @Test
    void jsonAndMultipartShareQuotaWhilePollingRemainsIndependent() {
        var requests = new AtomicInteger();
        var mapper = new ObjectMapper();
        var transport = new BotConfig().telegramRestClient(mapper).mutate()
            .requestInterceptor((request, bytes, execution) -> {
                requests.incrementAndGet();
                var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                    "{\"ok\":true,\"result\":{\"message_id\":42}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    org.springframework.http.HttpStatus.OK);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                return response;
            }).build();
        var client = new TelegramClient(transport, mapper, "fixture", 2);
        var quota = io.github.resilience4j.ratelimiter.RateLimiter.of("fixture",
            io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                .limitForPeriod(2).limitRefreshPeriod(java.time.Duration.ofDays(1))
                .timeoutDuration(java.time.Duration.ZERO).build());
        org.springframework.test.util.ReflectionTestUtils.setField(client, "rateLimiter", quota);

        assertThat(client.callApi("sendMessage", Map.of("text", "fixture"))).isPresent();
        var multipart = new org.springframework.http.client.MultipartBodyBuilder();
        multipart.part("chat_id", "1");
        assertThat(client.callMultipartApi("sendPhoto", multipart.build())).isPresent();
        assertThatThrownBy(() -> client.sendMessageChecked(1, "blocked", null, null, null, null, false))
            .isInstanceOfSatisfying(TelegramClient.LocalSendRefusalException.class, e ->
                assertThat(e.isRateLimit()).isFalse());
        assertThat(client.callApi("getUpdates", Map.of())).isPresent();
        assertThat(requests).hasValue(3);
    }

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
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void transportErrorsKeepTheTokenOutOfLogsAndExceptions(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        var mapper = new ObjectMapper();
        var transport = new BotConfig().telegramRestClient(mapper).mutate()
            .baseUrl("http://127.0.0.1:" + port).build();
        String token = "synthetic-secret-fixture";
        var client = new TelegramClient(transport, mapper, token, 0);

        assertThatThrownBy(() -> client.callApi("sendMessage", Map.of("text", "fixture")))
            .isInstanceOfSatisfying(TelegramApiException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(-1);
                assertThat(error.getMessage()).doesNotContain(token);
            });
        var multipart = new org.springframework.http.client.MultipartBodyBuilder();
        multipart.part("chat_id", "1");
        assertThatThrownBy(() -> client.callMultipartApi("sendDocument", multipart.build()))
            .isInstanceOfSatisfying(TelegramApiException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(-1);
                assertThat(error.getMessage()).doesNotContain(token);
            });
        assertThat(output.getAll()).contains("transport failed").doesNotContain(token);
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
    @Test
    void floodWaitFromOneChatDoesNotSuppressAnotherChatsRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botfixture/sendMessage", exchange -> {
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
