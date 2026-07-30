package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * REST client for the agent backend.
 * <p>Uses Spring {@link RestClient} (same pattern as the telegram-bot's AgentBackendClient).
 * All methods call the backend REST API at {@code /api/v1/agent/...}.
 */
@Component
@Slf4j
public class BackendClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BackendClient(@Qualifier("backendRestClient") RestClient restClient,
                         ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the backend and return the response text.
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
                return "Error: empty response from backend";
            }

            JsonNode node = objectMapper.readTree(responseJson);
            JsonNode responseField = node.get("response");
            if (responseField == null || responseField.isNull()) {
                responseField = node.get("content");
            }
            if (responseField == null || responseField.isNull()) {
                return "Error: missing 'response' field in backend reply";
            }
            return responseField.asText();
        } catch (Exception e) {
            log.error("Backend chat failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Stream chat response via SSE.
     * <p>This is a <strong>blocking</strong> call — run it in a separate thread.
     *
     * @param message  the user's message
     * @param sessionId the session UUID (may be null)
     * @param onToken  called for each token chunk
     * @param onTool   called for tool_start / tool_result events (combined info)
     * @param onDone   called when the stream completes
     */
    public void chatStream(String message, String sessionId,
                           Consumer<String> onToken,
                           Consumer<String> onTool,
                           Runnable onDone) {
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
                onDone.run();
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).strip();
                    if (data.isEmpty() || "data:".equals(data) || "[DONE]".equals(data)) {
                        if ("[DONE]".equals(data)) {
                            onDone.run();
                            return;
                        }
                        continue;
                    }
                    try {
                        JsonNode event = objectMapper.readTree(data);
                        String type = event.path("type").asText("");

                        if ("error".equalsIgnoreCase(type)) {
                            String errorMsg = event.path("error").asText(
                                event.path("message").asText("Unknown stream error"));
                            onTool.accept("ERROR: " + errorMsg);
                            continue;
                        }

                        if ("tool_start".equalsIgnoreCase(type)) {
                            String toolName = event.path("toolName").asText("");
                            if (!toolName.isEmpty()) {
                                onTool.accept("🔧 " + toolName);
                            }
                            continue;
                        }

                        if ("tool_result".equalsIgnoreCase(type)) {
                            String toolName = event.path("toolName").asText("");
                            String toolResult = event.path("toolResult").asText("");
                            String preview = toolResult.length() > 200
                                ? toolResult.substring(0, 200) + "…" : toolResult;
                            onTool.accept("✅ " + toolName + ": " + preview);
                            continue;
                        }

                        if ("done".equalsIgnoreCase(type)) {
                            onDone.run();
                            return;
                        }

                        // Default: token event
                        JsonNode tokenNode = event.get("token");
                        if (tokenNode != null && !tokenNode.isNull()) {
                            String token = tokenNode.asText();
                            onToken.accept(token);
                        }
                    } catch (Exception parseEx) {
                        log.warn("Failed to parse SSE data line: {}", data, parseEx);
                    }
                }
                // Stream ended without explicit "done" event
                onDone.run();
            }
        } catch (Exception e) {
            log.error("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
            onTool.accept("ERROR: " + e.getMessage());
            onDone.run();
        }
    }

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    public String resetSession(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/session/{id}/reset", sessionId)
                .retrieve()
                .toBodilessEntity();
            return "Session reset: " + sessionId;
        } catch (Exception e) {
            log.error("resetSession failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String compressSession(String sessionId, String focus) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (focus != null && !focus.isBlank()) {
            body.put("focus", focus);
        }
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/session/{id}/compress", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null ? result : "Context compressed.";
        } catch (Exception e) {
            log.error("compressSession failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String undoTurns(String sessionId, int turns) {
        try {
            Integer deleted = restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{id}/undo")
                    .queryParam("turns", turns)
                    .build(sessionId))
                .retrieve()
                .body(Integer.class);
            return "Undid " + (deleted != null ? deleted : 0) + " messages.";
        } catch (Exception e) {
            log.error("undoTurns failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public JsonNode getContext(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{id}/context", sessionId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getContext failed: {}", e.getMessage());
            return null;
        }
    }

    public JsonNode getUsage(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{id}/usage", sessionId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getUsage failed: {}", e.getMessage());
            return null;
        }
    }

    public JsonNode listSessions(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/sessions/{userId}", userId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listSessions failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    // ------------------------------------------------------------------
    // Memory & skills
    // ------------------------------------------------------------------

    public JsonNode getMemory() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getMemory failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public JsonNode getSkills() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/skills")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("getSkills failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
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

    // ------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------

    public String createCheckpoint(String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description != null ? description : "Manual checkpoint");
        try {
            restClient.post()
                .uri("/api/v1/agent/checkpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return "Checkpoint created: " + description;
        } catch (Exception e) {
            log.error("createCheckpoint failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String listCheckpoints() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/checkpoint")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No checkpoints found.";
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray() || array.isEmpty()) return "No checkpoints found.";
            StringBuilder sb = new StringBuilder("Checkpoints:\n");
            for (JsonNode node : array) {
                String id = node.path("id").asText();
                String desc = node.path("description").asText();
                int files = node.path("fileCount").asInt();
                sb.append(String.format("- %s | %s | %d files%n", id, desc, files));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("listCheckpoints failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String restoreCheckpoint(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/checkpoint/{id}/restore", id)
                .retrieve()
                .toBodilessEntity();
            return "Checkpoint restored: " + id;
        } catch (Exception e) {
            log.error("restoreCheckpoint failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Approve / deny
    // ------------------------------------------------------------------

    public String approve(boolean all, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("all", all);
        if (scope != null && !scope.isBlank()) {
            body.put("scope", scope);
        }
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null ? result : "Approved.";
        } catch (Exception e) {
            log.error("approve failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String deny(boolean all) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("all", all);
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null ? result : "Denied.";
        } catch (Exception e) {
            log.error("deny failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Steer
    // ------------------------------------------------------------------

    public String steer(String message, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("text", message);
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null ? result : "Steer sent.";
        } catch (Exception e) {
            log.error("steer failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Health & admin
    // ------------------------------------------------------------------

    public boolean health() {
        try {
            String responseJson = restClient.get()
                .uri("/actuator/health/readiness")
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
            return true;
        } catch (Exception e) {
            log.warn("Backend health check failed: {}", e.getMessage());
            return false;
        }
    }

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

    // ------------------------------------------------------------------
    // Agents & insights
    // ------------------------------------------------------------------

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
    // Pretty-print helper
    // ------------------------------------------------------------------

    public String prettyPrint(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node != null ? node.toString() : "null";
        }
    }
}