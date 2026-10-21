package com.azhukov.agent.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Resolves an entity's declared official website from Wikidata's public API.
 */
@Component
public class OfficialWebsiteResolver {

    private static final String WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OfficialWebsiteResolver(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    OfficialWebsiteResolver(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Returns the P856 website for the best matching Wikidata entity, if present.
     */
    public String resolve(String entityName) throws IOException {
        String searchUrl = WIKIDATA_API_URL + "?action=wbsearchentities&language=en&format=json&limit=1&search="
            + URLEncoder.encode(entityName, StandardCharsets.UTF_8);
        JsonNode search = getJson(searchUrl);
        String entityId = search.path("search").path(0).path("id").asText("");
        if (entityId.isBlank()) {
            return null;
        }
        String entityUrl = WIKIDATA_API_URL + "?action=wbgetentities&format=json&props=claims&ids="
            + URLEncoder.encode(entityId, StandardCharsets.UTF_8);
        JsonNode websites = getJson(entityUrl).path("entities").path(entityId).path("claims").path("P856");
        if (!websites.isArray() || websites.isEmpty()) {
            return null;
        }
        return websites.path(0).path("mainsnak").path("datavalue").path("value").asText(null);
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("User-Agent", "WartzJavaAgent/1.0 (web-search official-site lookup)")
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Wikidata returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Wikidata request interrupted", exception);
        }
    }
}
