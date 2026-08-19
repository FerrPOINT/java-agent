package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

import jakarta.annotation.PostConstruct;

/**
 * Per-domain delegate covering agent runtime / diagnostics endpoints:
 * health check, restart, insights and background task launch, plus the
 * backend base URL used for diagnostics.
 */
@Service
@Slf4j
public class ModelApiClient extends BaseBackendClient {

    private final BotProperties properties;
    private String baseUrl;

    public ModelApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper, BotProperties properties) {
        super(restClient, objectMapper);
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        baseUrl = properties.getBackendUrl();
    }

    // ------------------------------------------------------------------
    // Health
    // ------------------------------------------------------------------

    /**
     * Check backend health.
     *
     * @return true if the backend is healthy, false otherwise
     */
    public boolean health() {
        try {
            String responseJson = restClient.get()
                .uri("/api/v1/agent/health")
                .retrieve()
                .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                return false;
            }

            JsonNode node = objectMapper.readTree(responseJson);
            JsonNode status = node.get("status");
            if (status != null) {
                String statusText = status.asText();
                return "UP".equalsIgnoreCase(statusText) || "OK".equalsIgnoreCase(statusText);
            }
            // If no status field, assume healthy if we got a response
            return true;
        } catch (Exception e) {
            log.warn("Backend health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Restart / insights / background
    // ------------------------------------------------------------------

    public String restart() {
        try {
            restClient.post()
                .uri("/api/v1/agent/restart")
                .retrieve()
                .toBodilessEntity();
            return "Agent restarting...";
        } catch (Exception e) {
            log.warn("restart failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public JsonNode getInsights() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/insights")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : objectNode();
        } catch (Exception e) {
            log.warn("getInsights failed: {}", e.getMessage());
            return objectNode();
        }
    }

    public String runBackground(String prompt, String sessionId) {
        Map<String, Object> body = body();
        body.put("prompt", prompt);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null ? result : "Background task started.";
        } catch (Exception e) {
            log.warn("runBackground failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /**
     * Expose the base URL (for diagnostics/logging).
     *
     * @return the backend base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}