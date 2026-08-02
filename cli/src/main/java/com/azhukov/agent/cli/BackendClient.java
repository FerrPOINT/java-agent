package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
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
 * <p>
 * Connection-level failures (backend down, timeout) are wrapped in
 * {@link BackendUnavailableException} so the REPL can show a friendly message.
 */
@Component
@Slf4j
public class BackendClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String backendUrl;

    @Autowired
    public BackendClient(@Qualifier("backendRestClient") RestClient restClient,
                         ObjectMapper objectMapper,
                         BackendProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.backendUrl = properties.getBackendUrl();
    }

    /**
     * Constructor for tests that don't need a BackendProperties.
     */
    public BackendClient(RestClient restClient,
                         ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.backendUrl = "http://localhost:8090";
    }

    // ------------------------------------------------------------------
    // Error wrapping
    // ------------------------------------------------------------------

    /**
     * Wrap connection-level exceptions into BackendUnavailableException.
     * Other exceptions are re-thrown as-is.
     */
    private RuntimeException wrapConnectionError(Exception e) {
        if (e instanceof ResourceAccessException
            || e instanceof java.net.ConnectException
            || e instanceof java.net.SocketTimeoutException
            || (e.getCause() instanceof java.net.ConnectException)
            || (e.getCause() instanceof java.net.SocketTimeoutException)) {
            return new BackendUnavailableException(backendUrl, e);
        }
        return new RuntimeException(e.getMessage(), e);
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * Send a chat message to the backend and return the response text.
     */
    public String chat(String message, String sessionId) {
        return chat(message, sessionId, null);
    }

    public String chat(String message, String sessionId, CliState state) {
        Map<String, Object> body = buildChatBody(message, sessionId, state);
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
        } catch (BackendUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
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
        chatStream(message, sessionId, null, onToken, onTool, onDone);
    }

    public void chatStream(String message, String sessionId, CliState state,
                           Consumer<String> onToken,
                           Consumer<String> onTool,
                           Runnable onDone) {
        Map<String, Object> body = buildChatBody(message, sessionId, state);

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
        } catch (BackendUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("chatStream failed for sessionId={}: {}", sessionId, e.getMessage());
            onTool.accept("ERROR: " + e.getMessage());
            onDone.run();
        }
    }

    private boolean isConnectionError(Throwable e) {
        if (e == null) return false;
        if (e instanceof ResourceAccessException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        return isConnectionError(e.getCause());
    }

    private Map<String, Object> buildChatBody(String message, String sessionId, CliState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        if (state != null) {
            body.put("reasoningEffort", state.getReasoningEffort());
            body.put("fastMode", state.isFastMode());
            body.put("voiceMode", state.isVoiceMode());
            if (state.getPersonality() != null && !state.getPersonality().isBlank()) {
                body.put("personality", state.getPersonality());
            }
            java.util.List<String> disabled = state.getToolStates().entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
            if (!disabled.isEmpty()) {
                body.put("disabledTools", disabled);
            }
            if (state.getCdpUrl() != null && !state.getCdpUrl().isBlank()) {
                body.put("cdpUrl", state.getCdpUrl());
            }
            if (state.getQueuedPrompt() != null && !state.getQueuedPrompt().isBlank()) {
                body.put("queuedPrompt", state.getQueuedPrompt());
            }
            if (state.getActiveGoal() != null && !state.getActiveGoal().isBlank()) {
                body.put("subgoal", state.getActiveGoal());
            }
        }
        return body;
    }

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    public String createSession() {
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of())
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return null;
            JsonNode node = objectMapper.readTree(json);
            return node.path("id").asText(null);
        } catch (BackendUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("createSession failed: {}", e.getMessage());
            return null;
        }
    }

    public String resetSession(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/session/{id}/reset", sessionId)
                .retrieve()
                .toBodilessEntity();
            return "Session reset: " + sessionId;
        } catch (Exception e) {
            return handleErr("resetSession", e);
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
            return handleErr("compressSession", e);
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
            return handleErr("undoTurns", e);
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
    // Model switching (C1)
    // ------------------------------------------------------------------

    /**
     * Switch the model (and optionally provider) for the current session.
     *
     * @param sessionId the session UUID
     * @param model      the new model name
     * @param provider   the new provider name (optional, may be null/blank)
     * @return result message
     */
    public String switchModel(String sessionId, String model, String provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("model", model);
        if (provider != null && !provider.isBlank()) {
            body.put("provider", provider);
        }
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return "Model switched to: " + model;
            }
            JsonNode node = objectMapper.readTree(json);
            boolean ok = node.path("ok").asBoolean(false);
            if (ok) {
                String m = node.path("model").asText(model);
                String p = node.path("provider").asText("");
                return "Model switched to: " + m + (p.isBlank() ? "" : " (provider: " + p + ")");
            }
            String error = node.path("error").asText("Model switching failed");
            return "Model switching failed: " + error;
        } catch (BackendUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("switchModel failed: {}", e.getMessage());
            return "Error switching model: " + e.getMessage();
        }
    }

    /**
     * Get current model info for a session.
     */
    public String getCurrentModel(String sessionId) {
        try {
            String json = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/agent/model")
                    .queryParam("sessionId", sessionId)
                    .build())
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No model info available.";
            return prettyPrint(objectMapper.readTree(json));
        } catch (Exception e) {
            log.error("getCurrentModel failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Background task (C2)
    // ------------------------------------------------------------------

    public String backgroundTask(String prompt, String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("sessionId", sessionId);
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return "Background task started. Session: " + (result != null ? result : "unknown");
        } catch (Exception e) {
            return handleErr("backgroundTask", e);
        }
    }

    // ------------------------------------------------------------------
    // Branch session (C2)
    // ------------------------------------------------------------------

    public String branchSession(String sessionId, String name) {
        try {
            String result;
            if (name != null && !name.isBlank()) {
                result = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{id}/branch")
                        .queryParam("name", name)
                        .build(sessionId))
                    .retrieve()
                    .body(String.class);
            } else {
                result = restClient.post()
                    .uri("/api/v1/agent/session/{id}/branch", sessionId)
                    .retrieve()
                    .body(String.class);
            }
            return "Session branched: " + (result != null ? result : sessionId);
        } catch (Exception e) {
            return handleErr("branchSession", e);
        }
    }

    // ------------------------------------------------------------------
    // Cron jobs (C2)
    // ------------------------------------------------------------------

    public String listCronJobs() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/cron")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No cron jobs found.";
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray() || array.isEmpty()) return "No cron jobs found.";
            StringBuilder sb = new StringBuilder("Cron jobs:\n");
            for (JsonNode node : array) {
                String id = node.path("id").asText();
                String jobName = node.path("name").asText();
                String schedule = node.path("schedule").asText();
                boolean enabled = node.path("enabled").asBoolean();
                sb.append(String.format("- %s | %s | %s | %s%n", id, jobName, schedule,
                    enabled ? "enabled" : "paused"));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return handleErr("listCronJobs", e);
        }
    }

    public String pauseCronJob(String jobId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/cron/{id}/pause", jobId)
                .retrieve()
                .toBodilessEntity();
            return "Cron job paused: " + jobId;
        } catch (Exception e) {
            return handleErr("pauseCronJob", e);
        }
    }

    public String resumeCronJob(String jobId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/cron/{id}/resume", jobId)
                .retrieve()
                .toBodilessEntity();
            return "Cron job resumed: " + jobId;
        } catch (Exception e) {
            return handleErr("resumeCronJob", e);
        }
    }

    public String deleteCronJob(String jobId) {
        try {
            restClient.delete()
                .uri("/api/v1/agent/cron/{id}", jobId)
                .retrieve()
                .toBodilessEntity();
            return "Cron job deleted: " + jobId;
        } catch (Exception e) {
            return handleErr("deleteCronJob", e);
        }
    }

    public String createCronJob(String name, String schedule, String prompt, String deliverTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("schedule", schedule);
        body.put("prompt", prompt);
        if (deliverTo != null && !deliverTo.isBlank()) {
            body.put("deliverTo", deliverTo);
        }
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/cron")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return "Cron job created: " + (result != null ? result : name);
        } catch (Exception e) {
            return handleErr("createCronJob", e);
        }
    }

    // ------------------------------------------------------------------
    // Memory management (C2)
    // ------------------------------------------------------------------

    public String approveMemory(String userId, String entryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("id", entryId);
        try {
            Boolean result = restClient.post()
                .uri("/api/v1/agent/memory/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Boolean.class);
            return (result != null && result) ? "Memory approved: " + entryId : "Memory approval failed: " + entryId;
        } catch (Exception e) {
            return handleErr("approveMemory", e);
        }
    }

    public String rejectMemory(String userId, String entryId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("id", entryId);
        try {
            Boolean result = restClient.post()
                .uri("/api/v1/agent/memory/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Boolean.class);
            return (result != null && result) ? "Memory rejected: " + entryId : "Memory rejection failed: " + entryId;
        } catch (Exception e) {
            return handleErr("rejectMemory", e);
        }
    }

    public String deleteMemory(String userId, String entryId) {
        try {
            restClient.delete()
                .uri("/api/v1/agent/memory/{userId}/{entryId}", userId, entryId)
                .retrieve()
                .toBodilessEntity();
            return "Memory deleted: " + entryId;
        } catch (Exception e) {
            return handleErr("deleteMemory", e);
        }
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

    // ------------------------------------------------------------------
    // Approvals (C2)
    // ------------------------------------------------------------------

    public JsonNode listPendingApprovals() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/approvals/pending")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listPendingApprovals failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    public String approveTool(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/approvals/{sessionId}/approve", sessionId)
                .retrieve()
                .toBodilessEntity();
            return "Tool approved for session: " + sessionId;
        } catch (Exception e) {
            return handleErr("approveTool", e);
        }
    }

    public String denyTool(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/approvals/{sessionId}/deny", sessionId)
                .retrieve()
                .toBodilessEntity();
            return "Tool denied for session: " + sessionId;
        } catch (Exception e) {
            return handleErr("denyTool", e);
        }
    }

    // ------------------------------------------------------------------
    // Bundle install / uninstall (C2)
    // ------------------------------------------------------------------

    public String installBundle(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bundleName", name);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Bundle installed: " + name;
            JsonNode node = objectMapper.readTree(json);
            boolean ok = node.path("ok").asBoolean(false);
            if (ok) return node.path("message").asText("Bundle installed: " + name);
            return "Bundle install failed: " + node.path("error").asText("unknown error");
        } catch (Exception e) {
            return handleErr("installBundle", e);
        }
    }

    public String uninstallBundle(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bundleName", name);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/bundles/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Bundle uninstalled: " + name;
            JsonNode node = objectMapper.readTree(json);
            boolean ok = node.path("ok").asBoolean(false);
            if (ok) return node.path("message").asText("Bundle uninstalled: " + name);
            return "Bundle uninstall failed: " + node.path("error").asText("unknown error");
        } catch (Exception e) {
            return handleErr("uninstallBundle", e);
        }
    }

    // ------------------------------------------------------------------
    // Delete checkpoint (C2)
    // ------------------------------------------------------------------

    public String deleteCheckpoint(String checkpointId) {
        try {
            restClient.delete()
                .uri("/api/v1/agent/checkpoint/{id}", checkpointId)
                .retrieve()
                .toBodilessEntity();
            return "Checkpoint deleted: " + checkpointId;
        } catch (Exception e) {
            return handleErr("deleteCheckpoint", e);
        }
    }

    // ------------------------------------------------------------------
    // Stop agent (C3)
    // ------------------------------------------------------------------

    public String stopAgent(String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("sessionId", sessionId);
        }
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Agent stopped.";
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Agent stopped.");
        } catch (Exception e) {
            return handleErr("stopAgent", e);
        }
    }

    // ------------------------------------------------------------------
    // Skill content (C6)
    // ------------------------------------------------------------------

    public String getSkillContent(String skillName) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/skills/{name}", skillName)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Skill not found: " + skillName;
            JsonNode node = objectMapper.readTree(json);
            boolean ok = node.path("ok").asBoolean(false);
            if (!ok) return "Skill not found: " + skillName;
            return node.path("content").asText("(empty skill)");
        } catch (Exception e) {
            return handleErr("getSkillContent", e);
        }
    }

    // ------------------------------------------------------------------
    // Memory & skills (existing)
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
            return handleErr("createCheckpoint", e);
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
            return handleErr("listCheckpoints", e);
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
            return handleErr("restoreCheckpoint", e);
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
            return handleErr("approve", e);
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
            return handleErr("deny", e);
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
            return handleErr("steer", e);
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

    public String config() {
        try {
            JsonNode node = restClient.get()
                .uri("/api/v1/agent/config")
                .retrieve()
                .body(JsonNode.class);
            if (node == null) {
                return "Config: no response from backend.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Agent config:\n");
            sb.append("  Name: ").append(node.path("name").asText("unknown")).append("\n");
            sb.append("  Model: ").append(node.path("model").asText("unknown")).append("\n");
            sb.append("  Provider: ").append(node.path("provider").asText("unknown")).append("\n");
            sb.append("  Base URL: ").append(node.path("baseUrl").asText("unknown")).append("\n");
            sb.append("  Max turns: ").append(node.path("maxTurns").asInt(-1)).append("\n");
            sb.append("  Max model calls/turn: ").append(node.path("maxModelCallsPerTurn").asInt(-1)).append("\n");
            sb.append("  Max tokens: ").append(node.path("maxTokens").asInt(-1)).append("\n");
            sb.append("  Temperature: ").append(node.path("temperature").asDouble(-1)).append("\n");
            sb.append("  Timeout: ").append(node.path("timeoutSeconds").asInt(-1)).append("s\n");
            sb.append("  Reasoning config: ").append(node.path("reasoningConfig").asText("unknown")).append("\n");
            sb.append("  Features:\n");
            JsonNode features = node.path("features");
            features.fieldNames().forEachRemaining(name ->
                sb.append("    ").append(name).append(": ")
                  .append(features.path(name).asBoolean(false) ? "ON" : "OFF").append("\n"));
            return sb.toString();
        } catch (Exception e) {
            return handleErr("config", e);
        }
    }

    public String doctor() {
        try {
            JsonNode node = restClient.get()
                .uri("/api/v1/agent/doctor")
                .retrieve()
                .body(JsonNode.class);
            if (node == null) {
                return "Doctor: no response from backend.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Doctor report:\n");
            sb.append("  Backend: ").append(node.path("status").asText("unknown")).append("\n");
            sb.append("  Name: ").append(node.path("name").asText("unknown")).append("\n");
            sb.append("  Version: ").append(node.path("version").asText("unknown")).append("\n");
            sb.append("  Model: ").append(node.path("model").asText("unknown")).append("\n");
            sb.append("  Provider: ").append(node.path("provider").asText("unknown")).append("\n");
            sb.append("  Max turns: ").append(node.path("maxTurns").asInt(-1)).append("\n");
            sb.append("  Max model calls/turn: ").append(node.path("maxModelCallsPerTurn").asInt(-1)).append("\n");
            sb.append("  Memory: ").append(node.path("memoryEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
            sb.append("  TTS: ").append(node.path("ttsEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
            sb.append("  Transcription: ").append(node.path("transcriptionEnabled").asBoolean(false) ? "ON" : "OFF").append("\n");
            sb.append("  Skills loaded: ").append(node.path("skillCount").asInt(-1)).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return handleErr("doctor", e);
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
            return handleErr("restart", e);
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
            return handleErr("reloadMcp", e);
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
            return handleErr("reloadSkills", e);
        }
    }

    public String reloadAll() {
        try {
            restClient.post()
                .uri("/api/v1/agent/reload")
                .retrieve()
                .toBodilessEntity();
            return "Skills and MCP servers reloaded.";
        } catch (Exception e) {
            return handleErr("reloadAll", e);
        }
    }

    public String diff(String leftId, String rightId) {
        try {
            String json = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/api/v1/agent/diff")
                    .queryParam("left", leftId)
                    .queryParam("right", rightId)
                    .build())
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No diff data.";
            return prettyPrint(objectMapper.readTree(json));
        } catch (Exception e) {
            return handleErr("diff", e);
        }
    }

    /** @deprecated use {@link #diff(String, String)} — scope was never used by backend */
    @Deprecated(since = "0.0.1", forRemoval = true)
    public String diff(String leftId, String rightId, String scope) {
        return diff(leftId, rightId);
    }

    public String getCredits() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/credits")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No credits data.";
            JsonNode node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder("Credits summary:\n");
            sb.append("  Total cost: $").append(node.path("totalCost").asDouble(0)).append("\n");
            sb.append("  Total tokens: ").append(node.path("totalTokens").asInt(0)).append("\n");
            sb.append("  Total messages: ").append(node.path("totalMessages").asInt(0));
            return sb.toString();
        } catch (Exception e) {
            return handleErr("getCredits", e);
        }
    }

    public String curatorStatus() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/curator/status")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No curator status.";
            JsonNode node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder("Curator status:\n");
            sb.append("  Enabled: ").append(node.path("enabled").asBoolean(false)).append("\n");
            sb.append("  Paused: ").append(node.path("paused").asBoolean(false)).append("\n");
            sb.append("  Dry run: ").append(node.path("dryRun").asBoolean(false)).append("\n");
            sb.append("  Interval (hours): ").append(node.path("intervalHours").asInt(0)).append("\n");
            sb.append("  Min idle (hours): ").append(node.path("minIdleHours").asInt(0)).append("\n");
            sb.append("  Stale after (days): ").append(node.path("staleAfterDays").asInt(0)).append("\n");
            sb.append("  Archive after (days): ").append(node.path("archiveAfterDays").asInt(0));
            return sb.toString();
        } catch (Exception e) {
            return handleErr("curatorStatus", e);
        }
    }

    public String curatorRun() {
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/curator/run")
                .retrieve()
                .body(String.class);
            return result != null ? result : "Curator cycle completed.";
        } catch (Exception e) {
            return handleErr("curatorRun", e);
        }
    }

    public String curatorPause() {
        try {
            restClient.post()
                .uri("/api/v1/agent/curator/pause")
                .retrieve()
                .toBodilessEntity();
            return "Curator paused.";
        } catch (Exception e) {
            return handleErr("curatorPause", e);
        }
    }

    public String curatorResume() {
        try {
            restClient.post()
                .uri("/api/v1/agent/curator/resume")
                .retrieve()
                .toBodilessEntity();
            return "Curator resumed.";
        } catch (Exception e) {
            return handleErr("curatorResume", e);
        }
    }

    // ── Kanban ──
    public String kanbanList() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/kanban")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Kanban board is empty.";
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray() || array.isEmpty()) return "Kanban board is empty.";
            StringBuilder sb = new StringBuilder("Kanban board:\n");
            for (JsonNode item : array) {
                String id = item.path("id").asText("?");
                String title = item.path("title").asText("?");
                String status = item.path("status").asText("?");
                String priority = item.path("priority").asText("?");
                sb.append("  [").append(status).append("] ")
                    .append(title)
                    .append(" (").append(priority).append(", id: ").append(id).append(")\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return handleErr("kanbanList", e);
        }
    }

    public String kanbanAdd(String text) {
        try {
            Map<String, Object> body = Map.of("text", text);
            String json = restClient.post()
                .uri("/api/v1/agent/kanban/add")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json != null && !json.isBlank()) {
                JsonNode node = objectMapper.readTree(json);
                String id = node.path("id").asText("?");
                return "Task added: " + text + " (id: " + id + ")";
            }
            return "Task added: " + text;
        } catch (Exception e) {
            return handleErr("kanbanAdd", e);
        }
    }

    public String kanbanDone(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/kanban/done/{id}", id)
                .retrieve()
                .toBodilessEntity();
            return "Task " + id + " marked done.";
        } catch (Exception e) {
            return handleErr("kanbanDone", e);
        }
    }

    public String kanbanClear() {
        try {
            restClient.delete()
                .uri("/api/v1/agent/kanban")
                .retrieve()
                .toBodilessEntity();
            return "Kanban board cleared.";
        } catch (Exception e) {
            return handleErr("kanbanClear", e);
        }
    }

    // ── Codex Runtime ──
    public String codexRuntimeStatus() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/codex-runtime")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No runtime data.";
            JsonNode node = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder("Codex runtime:\n");
            sb.append("  Model: ").append(node.path("model").asText("?")).append("\n");
            sb.append("  Provider: ").append(node.path("provider").asText("?")).append("\n");
            sb.append("  Max retries: ").append(node.path("maxRetries").asInt(0)).append("\n");
            sb.append("  Max tokens: ").append(node.path("maxTokens").asInt(0)).append("\n");
            sb.append("  Timeout (seconds): ").append(node.path("timeoutSeconds").asInt(0));
            String override = node.path("modelOverride").asText(null);
            if (override != null) {
                sb.append("\n  Model override: ").append(override);
            }
            return sb.toString();
        } catch (Exception e) {
            return handleErr("codexRuntimeStatus", e);
        }
    }

    public String codexRuntimeModel(String modelName) {
        try {
            Map<String, Object> body = Map.of("model", modelName);
            restClient.post()
                .uri("/api/v1/agent/codex-runtime/model")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return "Codex runtime model set: " + modelName;
        } catch (Exception e) {
            return handleErr("codexRuntimeModel", e);
        }
    }

    public String codexRuntimeReset() {
        try {
            restClient.post()
                .uri("/api/v1/agent/codex-runtime/reset")
                .retrieve()
                .toBodilessEntity();
            return "Codex runtime reset.";
        } catch (Exception e) {
            return handleErr("codexRuntimeReset", e);
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

    // ------------------------------------------------------------------
    // Backend URL (for error messages)
    // ------------------------------------------------------------------

    public String getBackendUrl() {
        return backendUrl;
    }

    // ------------------------------------------------------------------
    // Error handler helper
    // ------------------------------------------------------------------

    private String handleErr(String method, Exception e) {
        if (isConnectionError(e)) {
            throw new BackendUnavailableException(backendUrl, e);
        }
        log.error("{} failed: {}", method, e.getMessage());
        return "Error: " + e.getMessage();
    }

    // ------------------------------------------------------------------
    // P1-4: New backend methods for 15 additional slash commands
    // ------------------------------------------------------------------

    /**
     * Retry: resend the last user message to the agent.
     */
    public String retry(String sessionId, String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) {
            return "No previous message to retry.";
        }
        return chat(lastMessage, sessionId);
    }

    /**
     * Set session title.
     */
    public String setTitle(String sessionId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("title", title);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/session/title")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Title set: " + title;
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Title set: " + title);
        } catch (Exception e) {
            return handleErr("setTitle", e);
        }
    }

    /**
     * Queue a prompt for the next turn.
     */
    public String queuePrompt(String sessionId, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("prompt", prompt);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/queue")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Prompt queued for next turn.";
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Prompt queued for next turn.");
        } catch (Exception e) {
            return handleErr("queuePrompt", e);
        }
    }

    /**
     * Create a state snapshot.
     */
    public String createSnapshot(String sessionId, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        if (description != null && !description.isBlank()) {
            body.put("description", description);
        }
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Snapshot created.";
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Snapshot created.");
        } catch (Exception e) {
            return handleErr("createSnapshot", e);
        }
    }

    /**
     * Set personality (system prompt injection).
     */
    public String setPersonality(String sessionId, String personality) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("personality", personality);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/personality")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Personality set: " + personality;
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Personality set: " + personality);
        } catch (Exception e) {
            return handleErr("setPersonality", e);
        }
    }

    /**
     * Set reasoning effort level.
     */
    public String setReasoningEffort(String sessionId, String level) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("reasoningEffort", level);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Reasoning effort set: " + level;
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Reasoning effort set: " + level);
        } catch (Exception e) {
            return handleErr("setReasoningEffort", e);
        }
    }

    /**
     * Toggle fast mode.
     */
    public String setFastMode(String sessionId, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("fastMode", enabled);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/fast-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Fast mode: " + (enabled ? "ON" : "OFF");
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Fast mode: " + (enabled ? "ON" : "OFF"));
        } catch (Exception e) {
            return handleErr("setFastMode", e);
        }
    }

    /**
     * Toggle voice mode.
     */
    public String setVoiceMode(String sessionId, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("voiceMode", enabled);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/voice-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Voice mode: " + (enabled ? "ON" : "OFF");
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Voice mode: " + (enabled ? "ON" : "OFF"));
        } catch (Exception e) {
            return handleErr("setVoiceMode", e);
        }
    }

    /**
     * Connect browser tools to CDP.
     */
    public String connectBrowser(String cdpUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cdpUrl", cdpUrl);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/browser/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Browser connected: " + cdpUrl;
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Browser connected: " + cdpUrl);
        } catch (Exception e) {
            return handleErr("connectBrowser", e);
        }
    }

    /**
     * List installed plugins.
     */
    public JsonNode listPlugins() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/plugins")
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listPlugins failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * List available tools.
     */
    public JsonNode listTools(String sessionId) {
        try {
            String uri = sessionId != null && !sessionId.isBlank()
                ? "/api/v1/agent/tools?sessionId=" + sessionId
                : "/api/v1/agent/tools";
            String json = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return objectMapper.createArrayNode();
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.error("listTools failed: {}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    /**
     * Enable or disable a tool.
     */
    public String toggleTool(String sessionId, String toolName, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("toolName", toolName);
        body.put("enabled", enabled);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/tools/toggle")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) {
                return "Tool " + toolName + ": " + (enabled ? "enabled" : "disabled");
            }
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText(
                "Tool " + toolName + ": " + (enabled ? "enabled" : "disabled"));
        } catch (Exception e) {
            return handleErr("toggleTool", e);
        }
    }

    /**
     * Add criteria to active goal (subgoal).
     */
    public String addSubgoal(String sessionId, String criteria) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("criteria", criteria);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Subgoal added: " + criteria;
            JsonNode node = objectMapper.readTree(json);
            return node.path("message").asText("Subgoal added: " + criteria);
        } catch (Exception e) {
            return handleErr("addSubgoal", e);
        }
    }
}