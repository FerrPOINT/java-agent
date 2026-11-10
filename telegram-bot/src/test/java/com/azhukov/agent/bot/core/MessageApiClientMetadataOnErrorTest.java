package com.azhukov.agent.bot.core;

import com.azhukov.agent.shared.http.BackendRestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0: metadata received over SSE BEFORE an error event must survive into the
 * returned ChatResult, so the bot can render the footer model on failed turns.
 *
 * <p>Drives the REAL SSE parser of {@link MessageApiClient#chatStream} against a
 * local HTTP server that replays the stream the backend now produces:
 * <pre>
 *   event:metadata  (early, pre-model-call — carries modelUsed)
 *   event:error     (model failed — carries no metadata)
 * </pre>
 */
class MessageApiClientMetadataOnErrorTest {

    private HttpServer server;
    private RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        restClient = BackendRestClientFactory.createStreaming(
            "http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void serveSse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String data : events) {
            body.append("data:").append(data).append("\n\n");
        }
        createContextHandler(body.toString());
    }

    // Small indirection to keep the compiler happy about helper naming
    private void createContextHandler(String payload) {
        server.createContext("/api/v1/agent/chat/stream", exchange -> {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
                os.flush();
            }
        });
        server.start();
    }

    @Test
    void chatStream_metadataBeforeError_isPreservedInResult() {
        serveSse(
            "{\"type\":\"metadata\",\"modelUsed\":\"kimi-k2.6\",\"contextTokens\":1200,\"contextLength\":262144,"
                + "\"sessionId\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"type\":\"error\",\"error\":\"Model call failed: usage limit\"}"
        );

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        AgentBackendClient.ChatResult result = client.chatStream(
            "hi", null, null,
            token -> { },              // tokenConsumer
            toolCall -> { },           // toolCallConsumer
            (name, preview) -> { },    // toolResultConsumer
            retry -> { },              // retryConsumer
            review -> { },             // reviewConsumer
            clarify -> { },            // clarifyConsumer (blocking clarify parity)
            complete -> { },           // onComplete
            errorRef::set              // onError
        );

        // The error must be surfaced to the consumer
        assertThat(errorRef.get()).isNotNull();
        assertThat(errorRef.get().getMessage()).contains("usage limit");
        // ...AND the pre-error metadata must survive into the result
        assertThat(result.modelUsed()).isEqualTo("kimi-k2.6");
        assertThat(result.contextTokens()).isEqualTo(1200);
        assertThat(result.contextLength()).isEqualTo(262144);
        assertThat(result.backendSessionId())
            .isEqualTo(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void onlyDoneConfirmsCompletion(boolean sendDone) {
        String token = "{\"type\":\"token\",\"token\":\"partial\"}";
        if (sendDone) serveSse(token, "{\"type\":\"done\"}");
        else serveSse(token);
        var complete = new AtomicReference<AgentBackendClient.ChatResult>();
        var error = new AtomicReference<Throwable>();
        var client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());

        var result = client.chatStream("hi", null, null, text -> {}, tool -> {}, (name, preview) -> {},
            retry -> {}, review -> {}, clarify -> {}, complete::set, error::set);

        assertThat(result.content()).isEqualTo("partial");
        assertThat(result.turnSucceeded()).isEqualTo(sendDone);
        if (sendDone) {
            assertThat(complete.get()).isSameAs(result);
            assertThat(error.get()).isNull();
        } else {
            assertThat(complete.get()).isNull();
            assertThat(error.get()).isInstanceOf(java.io.EOFException.class);
        }
    }

    @Test
    void doesNotReplayPostWhenServerReadsRequestAndClosesBeforeHeaders() {
        var requests = new AtomicInteger();
        server.createContext("/api/v1/agent/chat/stream", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (requests.incrementAndGet() == 1) {
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                byte[] payload = "data:{\"type\":\"done\"}\n\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        server.start();
        var completed = new AtomicReference<AgentBackendClient.ChatResult>();
        var error = new AtomicReference<Throwable>();
        var client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());

        var result = client.chatStream("hi", null, null, token -> {}, tool -> {}, (name, preview) -> {},
            retry -> {}, review -> {}, clarify -> {}, completed::set, error::set);

        assertThat(requests).hasValue(1);
        assertThat(result.turnSucceeded()).isFalse();
        assertThat(completed.get()).isNull();
        assertThat(error.get()).isNotNull();
    }

    @Test
    void cancelledCallbackStopsReadingBeforeDone() {
        serveSse("{malformed", "{\"type\":\"token\",\"token\":\"partial\"}", "{\"type\":\"done\"}");
        var completed = new AtomicReference<AgentBackendClient.ChatResult>();
        var error = new AtomicReference<Throwable>();
        var cancellation = new CancellationException("stop");
        var client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());

        var result = client.chatStream("hi", null, null, token -> { throw cancellation; }, tool -> {},
            (name, preview) -> {}, retry -> {}, review -> {}, clarify -> {}, completed::set, error::set);

        assertThat(result.turnSucceeded()).isFalse();
        assertThat(completed.get()).isNull();
        assertThat(error.get()).isSameAs(cancellation);
    }

    @Test
    void backendInterruptDoesNotCompleteTheTurn() {
        serveSse("{\"type\":\"token\",\"token\":\"partial\"}",
            "{\"type\":\"interrupted\",\"message\":\"Turn cancelled by user.\"}", "{\"type\":\"done\"}");
        var completed = new AtomicReference<AgentBackendClient.ChatResult>();
        var error = new AtomicReference<Throwable>();
        var client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());

        var result = client.chatStream("hi", null, null, token -> {}, tool -> {},
            (name, preview) -> {}, retry -> {}, review -> {}, clarify -> {}, completed::set, error::set);

        assertThat(result.turnSucceeded()).isFalse();
        assertThat(completed.get()).isNull();
        assertThat(error.get()).isInstanceOf(CancellationException.class);
    }

    @Test
    void idleHttpReadTimesOutWithoutRepeatingPost() {
        var requests = new AtomicInteger();
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/agent/chat/stream", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(300));
        var shortClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .requestFactory(factory).build();
        var complete = new AtomicReference<AgentBackendClient.ChatResult>();
        var error = new AtomicReference<Throwable>();
        var client = new MessageApiClient(shortClient, mapper, new com.azhukov.agent.bot.config.BotProperties());

        try {
            var result = client.chatStream("hi", null, null, text -> {}, tool -> {}, (name, preview) -> {},
                retry -> {}, review -> {}, clarify -> {}, complete::set, error::set);
            assertThat(result.turnSucceeded()).isFalse();
            assertThat(error.get()).isInstanceOf(java.net.SocketTimeoutException.class);
            assertThat(complete.get()).isNull();
            assertThat(requests).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void streamingIdleTimeoutOutlivesTheRecommendedClarifyWindow() {
        assertThat(BackendRestClientFactory.STREAMING_READ_TIMEOUT.toMillis())
            .isGreaterThan(Duration.ofDays(7).toMillis());
    }

    @Test
    void chatStream_reviewForwardsHumanSummaryToConsumer() {
        String summary = "💾 Self-improvement review: Memory updated";
        serveSse(
            "{\"type\":\"review\",\"error\":\"" + summary + "\"}",
            "{\"type\":\"done\"}"
        );
        AtomicReference<String> review = new AtomicReference<>();

        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, toolCall -> { }, (name, preview) -> { },
            retry -> { }, review::set, clarify -> { }, complete -> { }, error -> { });

        assertThat(review.get()).isEqualTo(summary);
    }

    @Test
    void chatStream_clarifyCarriesSseSessionIdWhenOriginalRequestHasNoSession() {
        String backendSession = "550e8400-e29b-41d4-a716-446655440000";
        serveSse(
            "{\"type\":\"clarify\",\"sessionId\":\"" + backendSession + "\","
                + "\"error\":\"{\\\"clarifyId\\\":\\\"prompt-1\\\",\\\"question\\\":\\\"Target?\\\"}\"}",
            "{\"type\":\"done\"}"
        );
        AtomicReference<String> clarify = new AtomicReference<>();

        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, toolCall -> { }, (name, preview) -> { },
            retry -> { }, review -> { }, clarify::set, complete -> { }, error -> { });

        assertThat(clarify.get()).startsWith(backendSession + "\u0001");
        assertThat(clarify.get()).contains("\"clarifyId\":\"prompt-1\"");
    }

    @Test
    void chatStream_errorWithoutMetadata_returnsEmptyMetadata() {
        serveSse(
            "{\"type\":\"error\",\"error\":\"Model call failed: boom\"}"
        );

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        AgentBackendClient.ChatResult result = client.chatStream(
            "hi", null, null,
            token -> { }, toolCall -> { }, (name, preview) -> { }, retry -> { },
            review -> { }, clarify -> { }, complete -> { }, errorRef::set
        );

        assertThat(errorRef.get()).isNotNull();
        assertThat(result.modelUsed()).isNull();
        assertThat(result.content()).isNotNull();
    }
}
