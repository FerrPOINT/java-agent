package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for the agent backend.
 * Uses the qualified "backendRestClient" bean configured in {@link com.azhukov.agent.bot.config.BotConfig}.
 *
 * <p>POST to {@code /api/v1/agent/chat} with JSON body
 * {@code {"message":"...","sessionId":"..."}} (sessionId omitted when null).
 * Parses the {@code "response"} field from the returned JSON.
 *
 * <p>GET to {@code /api/v1/agent/health} for health checks.
 */
@Service
public class AgentBackendClient {

    private static final Logger log = LoggerFactory.getLogger(AgentBackendClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public AgentBackendClient(@Qualifier("backendRestClient") RestClient restClient,
                              ObjectMapper objectMapper,
                              BotProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = properties.getBackendUrl();
    }

    /**
     * Send a chat message to the agent backend.
     *
     * @param message   the user's message text
     * @param sessionId the session UUID (may be null — omitted from body when null)
     * @return the response text from the agent, or an error message on failure
     */
    public String chat(String message, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }

        try {
            String responseJson = restClient.post()
                .uri("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                log.warn("Backend chat returned empty response for sessionId={}", sessionId);
                return "Error: empty response from backend";
            }

            JsonNode node = objectMapper.readTree(responseJson);
            JsonNode responseField = node.get("response");
            if (responseField == null || responseField.isNull()) {
                log.warn("Backend chat response missing 'response' field: {}", responseJson);
                return "Error: missing 'response' field in backend reply";
            }

            return responseField.asText();
        } catch (Exception e) {
            log.error("Backend chat failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

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

    /**
     * Expose the base URL (for diagnostics/logging).
     *
     * @return the backend base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}