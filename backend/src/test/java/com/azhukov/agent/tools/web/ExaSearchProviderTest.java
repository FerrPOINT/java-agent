package com.azhukov.agent.tools.web;

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

import static org.assertj.core.api.Assertions.assertThat;

class ExaSearchProviderTest {

    @Test
    void parsesMcpSseSearchResponse() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, """
            event: message
            data: {"result":{"content":[{"type":"text","text":"Title: RareTerm docs\\nURL: https://example.com/rareterm\\nHighlights:\\nReference text"}]}}

            """));
        try {
            ExaSearchProvider provider = new ExaSearchProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", HttpClient.newHttpClient(), new ObjectMapper());

            List<Map<String, String>> results = provider.search("rareterm", 2);

            assertThat(results).containsExactly(Map.of("title", "RareTerm docs", "url", "https://example.com/rareterm",
                "description", "Reference text"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsProviderRateLimitFromMcpMetadata() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, """
            data: {"result":{"_meta":{"ai.exa/rateLimited":true},"content":[]}}
            """));
        try {
            ExaSearchProvider provider = new ExaSearchProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", HttpClient.newHttpClient(), new ObjectMapper());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.search("rareterm", 2))
                .isInstanceOf(IOException.class)
                .hasMessage("Exa returned HTTP 429");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", handler::handle);
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
