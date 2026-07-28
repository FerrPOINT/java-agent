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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

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
            String responseText;
            if (responseField == null || responseField.isNull()) {
                // Try "content" field (the actual ChatResponseDto field name)
                responseField = node.get("content");
            }
            if (responseField == null || responseField.isNull()) {
                log.warn("Backend chat response missing 'response' or 'content' field: {}", responseJson);
                return "Error: missing 'response' field in backend reply";
            }
            responseText = responseField.asText();

            // Check for memoryUpdated flag (Stage 7.3)
            JsonNode memoryUpdatedNode = node.get("memoryUpdated");
            if (memoryUpdatedNode != null && memoryUpdatedNode.asBoolean(false)) {
                responseText = responseText + "\n\n💾 Self-improvement review: Memory updated";
            }

            return responseText;
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

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    /**
     * Reset (clear) the conversation session on the backend.
     *
     * @param sessionId the session UUID
     * @return {@code true} if the reset succeeded, {@code false} on error
     */
    public boolean resetSession(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/session/{sessionId}/reset", sessionId)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("resetSession failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * Get the full conversation context for a session.
     *
     * @param sessionId the session UUID
     * @return the context as a {@link JsonNode}, or {@code null} on error
     */
    public JsonNode getContext(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{sessionId}/context", sessionId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getContext failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Get token/cost usage statistics for a session.
     *
     * @param sessionId the session UUID
     * @return the usage as a {@link JsonNode}, or {@code null} on error
     */
    public JsonNode getUsage(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{sessionId}/usage", sessionId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getUsage failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * List all sessions belonging to a user.
     *
     * @param userId the user identifier
     * @return an array of session summaries, or an empty array on error
     */
    public JsonNode listSessionsByUser(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/sessions/{userId}", userId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return objectMapper.createArrayNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listSessionsByUser failed for userId={}: {}", userId, e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    // ------------------------------------------------------------------
    // Memory & skills
    // ------------------------------------------------------------------

    /**
     * Get the list of memory entries from the backend.
     *
     * @return a {@link JsonNode} (array of strings), or an empty array on error
     */
    public JsonNode getMemory() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return objectMapper.createArrayNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getMemory failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * Get the list of available skills from the backend.
     *
     * @return a {@link JsonNode} (array of strings), or an empty array on error
     */
    public JsonNode getSkills() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/skills")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return objectMapper.createArrayNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getSkills failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    // ------------------------------------------------------------------
    // Streaming chat
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the agent backend and stream the response back
     * via Server-Sent Events.
     *
     * <p>This is a <strong>blocking</strong> call — the caller should run it
     * in a separate thread.
     *
     * @param message        the user's message text
     * @param sessionId      the session UUID (may be null)
     * @param tokenConsumer  called for each token received
     * @param onComplete     called when the stream completes successfully
     * @param onError        called with an exception if the stream fails
     */
    public void chatStream(String message,
                           String sessionId,
                           Consumer<String> tokenConsumer,
                           Runnable onComplete,
                           Consumer<Throwable> onError) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }

        try {
            InputStream is = restClient.post()
                .uri("/api/v1/agent/chat/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(InputStream.class);

            if (is == null) {
                onError.accept(new IllegalStateException("Backend stream returned null input stream"));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder dataBuilder = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).strip();
                        if (!data.isEmpty()) {
                            try {
                                JsonNode event = objectMapper.readTree(data);
                                String type = event.path("type").asText("");
                                if ("error".equalsIgnoreCase(type)) {
                                    String errorMsg = event.path("error").asText(
                                            event.path("message").asText("Unknown stream error"));
                                    onError.accept(new RuntimeException(errorMsg));
                                    return;
                                }
                                JsonNode tokenNode = event.get("token");
                                if (tokenNode != null && !tokenNode.isNull()) {
                                    tokenConsumer.accept(tokenNode.asText());
                                }
                                if ("done".equalsIgnoreCase(type)) {
                                    onComplete.run();
                                    return;
                                }
                            } catch (Exception parseEx) {
                                log.warn("Failed to parse SSE data line: {}", data, parseEx);
                            }
                        }
                    }
                }
                // Stream ended without explicit "done" event
                onComplete.run();
            }
        } catch (Exception e) {
            log.error("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
            onError.accept(e);
        }
    }

    // ------------------------------------------------------------------
    // Compress, undo, approve, deny, agents, insights
    // ------------------------------------------------------------------

    public String compressSession(String sessionId, String focus) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (focus != null && !focus.isBlank()) {
            body.put("focus", focus);
        }
        try {
            restClient.post()
            .uri("/api/v1/agent/session/{sessionId}/compress", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();
        return "Context compressed.";
        } catch (Exception e) {
            log.error("compressSession failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String undoTurns(String sessionId, int turns) {
        try {
            Integer deleted = restClient.post()
            .uri(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{sessionId}/undo")
                .queryParam("turns", turns)
                .build(sessionId))
            .retrieve()
            .body(Integer.class);
        return "Undid " + (deleted != null ? deleted : 0) + " messages.";
        } catch (Exception e) {
            log.error("undoTurns failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String approve(boolean all, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("all", all);
        if (scope != null) body.put("scope", scope);
        try {
            return restClient.post()
            .uri("/api/v1/agent/approve")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);
        } catch (Exception e) {
            log.error("approve failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String deny(boolean all) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("all", all);
        try {
            return restClient.post()
            .uri("/api/v1/agent/deny")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);
        } catch (Exception e) {
            log.error("deny failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public JsonNode listActiveAgents() {
        try {
            String json = restClient.get()
            .uri("/api/v1/agent/agents")
            .retrieve()
            .body(String.class);
        if (json == null || json.isBlank()) return objectMapper.createArrayNode();
        return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listActiveAgents failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public JsonNode getInsights() {
        try {
            String json = restClient.get()
            .uri("/api/v1/agent/insights")
            .retrieve()
            .body(String.class);
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getInsights failed: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: restart, reload, bundles, branch, background
    // ------------------------------------------------------------------

    public String restart() {
        try {
            restClient.post()
                .uri("/api/v1/agent/restart")
                .retrieve()
                .toBodilessEntity();
            return "Agent restarting...";
        } catch (Exception e) {
            log.error("restart failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String reloadMcp() {
        try {
            restClient.post()
                .uri("/api/v1/agent/reload-mcp")
                .retrieve()
                .toBodilessEntity();
            return "MCP servers reloaded.";
        } catch (Exception e) {
            log.error("reloadMcp failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String reloadSkills() {
        try {
            restClient.post()
                .uri("/api/v1/agent/reload-skills")
                .retrieve()
                .toBodilessEntity();
            return "Skills reloaded.";
        } catch (Exception e) {
            log.error("reloadSkills failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public JsonNode listBundles() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/bundles")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listBundles failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public String branchSession(String sessionId, String name) {
        try {
            String url = "/api/v1/agent/session/" + sessionId + "/branch";
            if (name != null && !name.isBlank()) {
                url += "?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
            }
            String json = restClient.post()
                .uri(url)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Branch created.";
            JsonNode node = objectMapper.readTree(json);
            JsonNode idNode = node.get("id");
            return idNode != null ? "Branched session: " + idNode.asText() : "Branch created.";
        } catch (Exception e) {
            log.error("branchSession failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String runBackground(String prompt, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
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
            log.error("runBackground failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Memory management (Stage 7.2)
    // ------------------------------------------------------------------

    public JsonNode listPendingMemory(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory/pending/{userId}", userId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listPendingMemory failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public boolean approvePendingMemory(String userId, String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("id", id);
        try {
            Boolean result = restClient.post()
                .uri("/api/v1/agent/memory/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("approvePendingMemory failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean rejectPendingMemory(String userId, String id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("id", id);
        try {
            Boolean result = restClient.post()
                .uri("/api/v1/agent/memory/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("rejectPendingMemory failed: {}", e.getMessage());
            return false;
        }
    }

    public void setMemoryApproval(boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        try {
            restClient.post()
                .uri("/api/v1/agent/memory/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("setMemoryApproval failed: {}", e.getMessage());
        }
    }

    public boolean isMemoryApprovalEnabled() {
        // Query current state — no dedicated endpoint, default false
        return false;
    }

    public JsonNode listAllMemory(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory/all/{userId}", userId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listAllMemory failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public boolean deleteMemory(String userId, String entryId) {
        try {
            restClient.delete()
                .uri("/api/v1/agent/memory/{userId}/{entryId}", userId, entryId)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.error("deleteMemory failed: {}", e.getMessage());
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