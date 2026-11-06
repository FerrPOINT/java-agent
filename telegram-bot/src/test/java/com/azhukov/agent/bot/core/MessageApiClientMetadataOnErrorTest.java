package com.azhukov.agent.bot.core;

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
        restClient = RestClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
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

    @Test
    void streamingIdleTimeoutOutlivesTheRecommendedClarifyWindow() {
        assertThat(MessageApiClient.STREAM_IDLE_TIMEOUT_MS)
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
    void refusedConnectionIsRetriedAndLaterSucceeds() throws Exception {
        var connects = new java.util.concurrent.atomic.AtomicInteger();
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory() {
            @Override public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri, org.springframework.http.HttpMethod method) throws IOException {
                if (connects.incrementAndGet() == 1) throw new java.net.ConnectException("refused");
                return super.createRequest(uri, method);
            }
        };
        serveSse("{\"type\":\"done\"}");
        var client = new MessageApiClient(RestClient.builder()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .requestFactory(requestFactory).build(), mapper, new com.azhukov.agent.bot.config.BotProperties());
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.chatStream("hi", null, null, token -> { }, tool -> { }, (name, preview) -> { },
            retry -> { }, review -> { }, clarify -> { }, result -> completed.set(true), error::set);
        assertThat(connects).hasValue(2);
        assertThat(completed).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    void acceptedPostWithEmptyResponseIsNotReplayed() {
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/v1/agent/chat/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var streamingClient = com.azhukov.agent.shared.http.BackendRestClientFactory.createStreaming(
            "http://127.0.0.1:" + server.getAddress().getPort(), "");
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        var client = new MessageApiClient(streamingClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, tool -> { }, (name, preview) -> { },
            retry -> { }, review -> { }, clarify -> { }, result -> { }, error -> errors.incrementAndGet());
        assertThat(accepted).as("POST accepted by backend").hasValue(1);
        assertThat(errors).hasValue(1);
    }

    @Test
    void acceptedPostWithLostResponseIsNotReplayed() {
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/v1/agent/chat/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            exchange.close();
        });
        server.start();
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        var streamingClient = com.azhukov.agent.shared.http.BackendRestClientFactory.createStreaming(
            "http://127.0.0.1:" + server.getAddress().getPort(), "");
        MessageApiClient client = new MessageApiClient(streamingClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, tool -> { }, (name, preview) -> { },
            retry -> { }, review -> { }, clarify -> { }, result -> { }, error -> errors.incrementAndGet());
        assertThat(errors).hasValue(1);
        assertThat(accepted).as("POST accepted by backend").hasValue(1);
    }

    @Test
    void errorCallbackFailureDoesNotInvokeErrorCallbackTwice() {
        serveSse("{\"type\":\"error\",\"error\":\"backend failed\"}");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.chatStream("hi", null, null,
            token -> { }, tool -> { }, (name, preview) -> { }, retry -> { }, review -> { }, clarify -> { },
            result -> { }, error -> { calls.incrementAndGet(); throw new IllegalStateException("error callback failed"); }))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("error callback failed");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void malformedSseDoesNotPreventFollowingValidCompletion() {
        serveSse("{invalid", "{\"type\":\"done\"}");
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, tool -> { }, (name, preview) -> { },
            retry -> { }, review -> { }, clarify -> { }, result -> completed.set(true), error::set);
        assertThat(completed).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    void exceptionFromToolCallbackIsNotSwallowedAsMalformedSse() {
        serveSse("{\"type\":\"tool_start\",\"toolName\":\"search\"}", "{\"type\":\"done\"}");
        AtomicReference<Throwable> error = new AtomicReference<>();
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        MessageApiClient client = new MessageApiClient(restClient, mapper, new com.azhukov.agent.bot.config.BotProperties());
        client.chatStream("hi", null, null, token -> { }, tool -> { throw new IllegalStateException("callback failure"); },
            (name, preview) -> { }, retry -> { }, review -> { }, clarify -> { },
            result -> completed.set(true), error::set);
        assertThat(error.get()).isNotNull().hasMessageContaining("callback failure");
        assertThat(completed).isFalse();
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
