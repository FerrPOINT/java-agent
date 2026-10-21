package com.azhukov.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySearchProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keylessModeSendsDocumentedHeaderAndNormalizesResults() throws Exception {
        AtomicReference<String> accessMode = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            accessMode.set(exchange.getRequestHeaders().getFirst("X-Tavily-Access-Mode"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                {"results":[{"title":"RareTerm guide","url":"https://example.com/rareterm","content":"Result content"}]}
                """);
        });
        try {
            TavilySearchProvider provider = provider(server, "");

            List<Map<String, String>> results = provider.search("rareterm", 3);

            assertThat(accessMode.get()).isEqualTo("keyless");
            assertThat(authorization.get()).isNull();
            JsonNode request = objectMapper.readTree(body.get());
            assertThat(request.path("query").asText()).isEqualTo("rareterm");
            assertThat(request.path("max_results").asInt()).isEqualTo(3);
            assertThat(results).containsExactly(Map.of("title", "RareTerm guide", "url", "https://example.com/rareterm",
                "description", "Result content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keyedModeDoesNotSendKeylessHeader() throws Exception {
        AtomicReference<String> accessMode = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            accessMode.set(exchange.getRequestHeaders().getFirst("X-Tavily-Access-Mode"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"results\":[]}");
        });
        try {
            TavilySearchProvider provider = provider(server, "test-key");

            assertThat(provider.search("query", 1)).isEmpty();
            assertThat(accessMode.get()).isNull();
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
        } finally {
            server.stop(0);
        }
    }

    private TavilySearchProvider provider(HttpServer server, String apiKey) {
        return new TavilySearchProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/search", apiKey,
            HttpClient.newHttpClient(), objectMapper);
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", handler::handle);
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
