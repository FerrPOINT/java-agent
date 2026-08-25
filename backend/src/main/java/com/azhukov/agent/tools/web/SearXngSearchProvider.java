package com.azhukov.agent.tools.web;

import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SearXNG search provider — queries a user-hosted SearXNG instance via JSON API.
 *
 * Mirrors Hermes plugins/web/searxng/provider.py — SearXNGWebSearchProvider.
 * API: GET {base_url}/search?q={query}&format=json
 * Results sorted by score descending, capped to limit.
 */
@Slf4j
public class SearXngSearchProvider {

    private final String baseUrl;
    private final UrlSafety urlSafety;
    private final HttpClient httpClient;

    public SearXngSearchProvider(String baseUrl, UrlSafety urlSafety) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.urlSafety = urlSafety;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Execute a search against the configured SearXNG instance.
     *
     * @param query the search query
     * @param limit maximum number of results
     * @return list of result maps with keys: title, url, description
     */
    public List<Map<String, String>> search(String query, int limit) throws IOException {
        if (!isAvailable()) {
            throw new IOException("SearXNG URL is not set");
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = baseUrl + "/search?q=" + encodedQuery + "&format=json&pageno=1";

        if (urlSafety != null && !urlSafety.isUrlAllowed(url)) {
            throw new IOException("URL is not allowed by safety policy: " + url);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("SearXNG returned HTTP " + response.statusCode());
            }
            return parseResults(response.body(), limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SearXNG request interrupted", e);
        }
    }

    private List<Map<String, String>> parseResults(String jsonBody, int limit) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonBody);
        JsonNode resultsNode = root.path("results");

        if (!resultsNode.isArray()) {
            return List.of();
        }

        // Sort by score descending (SearXNG may return score field)
        List<JsonNode> sorted = new ArrayList<>();
        resultsNode.forEach(sorted::add);
        sorted.sort((a, b) -> {
            double scoreA = a.path("score").asDouble(0.0);
            double scoreB = b.path("score").asDouble(0.0);
            return Double.compare(scoreB, scoreA);
        });

        List<Map<String, String>> out = new ArrayList<>();
        int count = 0;
        for (JsonNode r : sorted) {
            if (count >= limit) break;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", r.path("title").asText(""));
            item.put("url", r.path("url").asText(""));
            item.put("description", r.path("content").asText(""));
            out.add(item);
            count++;
        }

        log.info("SearXNG search: {} results (from {} raw, limit {})", out.size(), sorted.size(), limit);
        return out;
    }
}