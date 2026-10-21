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

class ParallelSearchProviderTest {

    @Test
    void sendsGeneralQueryThroughMcpAndNormalizesResults() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, """
            {"jsonrpc":"2.0","id":1,"result":{"content":[{"text":"{\\"results\\":[{\\"title\\":\\"RareTerm docs\\",\\"url\\":\\"https://example.com/rareterm\\",\\"excerpts\\":[\\"first\\",\\"second\\"]}]}"}]}}
            """));
        try {
            ParallelSearchProvider provider = new ParallelSearchProvider(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", HttpClient.newHttpClient(), new ObjectMapper());

            List<Map<String, String>> results = provider.search("rareterm", 2);

            assertThat(results).containsExactly(Map.of("title", "RareTerm docs", "url", "https://example.com/rareterm",
                "description", "first second"));
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
