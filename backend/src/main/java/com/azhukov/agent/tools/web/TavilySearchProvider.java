package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tavily's documented keyless API is an account-free fallback for general web search.
 */
public class TavilySearchProvider implements WebSearchProvider {

    private final String endpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    TavilySearchProvider(String endpoint, String apiKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public TavilySearchProvider(AgentProperties.WebProperties properties, ObjectMapper objectMapper) {
        this(properties.getTavilyUrl(), properties.getTavilyApiKey(), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getSearchTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), objectMapper);
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean isAvailable() {
        return !endpoint.isBlank();
    }

    @Override
    public List<Map<String, String>> search(String query, int limit) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Tavily URL is not set");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", query);
            payload.put("max_results", limit);
            payload.put("search_depth", "basic");
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (apiKey.isBlank()) {
                request.header("X-Tavily-Access-Mode", "keyless");
            } else {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Tavily returned HTTP " + response.statusCode());
            }
            return parseResults(response.body(), limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Tavily request interrupted", e);
        }
    }

    private List<Map<String, String>> parseResults(String body, int limit) throws IOException {
        JsonNode results = objectMapper.readTree(body).path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            if (url.isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", result.path("title").asText(""));
            item.put("url", url);
            item.put("description", result.path("content").asText(""));
            out.add(item);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
