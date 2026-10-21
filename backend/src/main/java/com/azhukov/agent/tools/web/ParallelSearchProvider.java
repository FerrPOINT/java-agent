package com.azhukov.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account-free Parallel MCP search. The random process session id is only for
 * provider rate limiting and is never derived from a user or query.
 */
public class ParallelSearchProvider implements WebSearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://search.parallel.ai/mcp";
    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String sessionId = UUID.randomUUID().toString().replace("-", "");

    ParallelSearchProvider(String endpoint, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ParallelSearchProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        this(DEFAULT_ENDPOINT, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper);
    }

    @Override
    public String name() {
        return "parallel";
    }

    @Override
    public boolean isAvailable() {
        return !endpoint.isBlank();
    }

    @Override
    public List<Map<String, String>> search(String query, int limit) throws IOException {
        try {
            Map<String, Object> arguments = Map.of("objective", query, "search_queries", List.of(query), "session_id", sessionId);
            Map<String, Object> payload = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "web_search", "arguments", arguments));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                .header("User-Agent", "WartzJavaAgent/1.0").timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Parallel returned HTTP " + response.statusCode());
            }
            return parseResults(response.body(), limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Parallel request interrupted", e);
        }
    }

    private List<Map<String, String>> parseResults(String body, int limit) throws IOException {
        JsonNode response = objectMapper.readTree(body);
        JsonNode content = response.path("result").path("content");
        if (!content.isArray() || content.isEmpty()) {
            return List.of();
        }
        JsonNode results = objectMapper.readTree(content.get(0).path("text").asText("{}")).path("results");
        List<Map<String, String>> parsed = new ArrayList<>();
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            List<String> excerpts = objectMapper.convertValue(result.path("excerpts"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            String description = String.join(" ", excerpts);
            parsed.add(Map.of("title", result.path("title").asText(""), "url", url, "description", description));
            if (parsed.size() >= limit) {
                break;
            }
        }
        return parsed;
    }
}
