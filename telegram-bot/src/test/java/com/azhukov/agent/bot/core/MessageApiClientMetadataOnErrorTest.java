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

        MessageApiClient client = new MessageApiClient(restClient, mapper);
        AgentBackendClient.ChatResult result = client.chatStream(
            "hi", null, null,
            token -> { },              // tokenConsumer
            toolCall -> { },           // toolCallConsumer
            (name, preview) -> { },    // toolResultConsumer
            retry -> { },              // retryConsumer
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
    void chatStream_errorWithoutMetadata_returnsEmptyMetadata() {
        serveSse(
            "{\"type\":\"error\",\"error\":\"Model call failed: boom\"}"
        );

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        MessageApiClient client = new MessageApiClient(restClient, mapper);
        AgentBackendClient.ChatResult result = client.chatStream(
            "hi", null, null,
            token -> { }, toolCall -> { }, (name, preview) -> { }, retry -> { },
            complete -> { }, errorRef::set
        );

        assertThat(errorRef.get()).isNotNull();
        assertThat(result.modelUsed()).isNull();
        assertThat(result.content()).isNotNull();
    }
}
