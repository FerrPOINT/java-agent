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
import java.util.regex.Pattern;

/** Account-free Exa MCP search, used after earlier general-web providers. */
public class ExaSearchProvider implements WebSearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://mcp.exa.ai/mcp";
    private static final Pattern SSE_DATA = Pattern.compile("(?m)^data: (.+)$");
    private final String endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    ExaSearchProvider(String endpoint, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ExaSearchProvider(ObjectMapper objectMapper, int timeoutSeconds) {
        this(DEFAULT_ENDPOINT, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).build(), objectMapper);
    }

    @Override
    public String name() {
        return "exa";
    }

    @Override
    public boolean isAvailable() {
        return !endpoint.isBlank();
    }

    @Override
    public List<Map<String, String>> search(String query, int limit) throws IOException {
        try {
            Map<String, Object> payload = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "web_search_exa", "arguments", Map.of("query", query, "numResults", limit)));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(endpoint))
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                .header("User-Agent", "WartzJavaAgent/1.0").timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Exa returned HTTP " + response.statusCode());
            }
            return parseResults(response.body(), limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Exa request interrupted", e);
        }
    }

    private List<Map<String, String>> parseResults(String body, int limit) throws IOException {
        String payload = extractSsePayload(body);
        JsonNode root = objectMapper.readTree(payload);
        if (root.path("result").path("_meta").path("ai.exa/rateLimited").asBoolean(false)) {
            throw new IOException("Exa returned HTTP 429");
        }
        JsonNode content = root.path("result").path("content");
        if (!content.isArray() || content.isEmpty()) {
            return List.of();
        }
        return parseText(content.get(0).path("text").asText(""), limit);
    }

    private String extractSsePayload(String body) throws IOException {
        if (body.strip().startsWith("{")) {
            return body;
        }
        var matcher = SSE_DATA.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IOException("Exa returned an unrecognized MCP response");
    }

    private List<Map<String, String>> parseText(String text, int limit) {
        List<Map<String, String>> results = new ArrayList<>();
        for (String block : text.split("\\R---\\R")) {
            String title = valueAfter(block, "Title:");
            String url = valueAfter(block, "URL:");
            String highlights = valueAfter(block, "Highlights:");
            if (!url.isBlank()) {
                results.add(Map.of("title", title, "url", url, "description", highlights));
            }
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    private String valueAfter(String block, String label) {
        int offset = block.indexOf(label);
        if (offset < 0) {
            return "";
        }
        String remainder = block.substring(offset + label.length()).strip();
        int nextLabel = remainder.indexOf("\nURL:");
        if (nextLabel < 0) nextLabel = remainder.indexOf("\nHighlights:");
        if (nextLabel < 0) nextLabel = remainder.indexOf("\nPublished:");
        if (nextLabel < 0) nextLabel = remainder.indexOf("\nAuthor:");
        return (nextLabel < 0 ? remainder : remainder.substring(0, nextLabel)).strip();
    }
}
