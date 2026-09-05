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
 * REST client for the agent backend — thin transport layer.
 * <p>
 * This class is responsible <strong>only</strong> for HTTP transport: building
 * requests, executing them, parsing JSON responses into {@link JsonNode}, and
 * wrapping connection-level failures into {@link BackendUnavailableException}.
 * <p>
 * All presentation/formatting (StringBuilder assembly, field extraction for CLI
 * display) lives in {@link BackendResponseFormatter}. The public {@code list*},
 * {@code getCredits}, {@code config}, {@code doctor}, etc. methods here are
 * thin wrappers that fetch JSON via the transport helpers and delegate
 * formatting to the formatter. They are preserved for backward compatibility
 * with existing tests and callers that mock these methods.
 * <p>
 * Uses Spring {@link RestClient} (same pattern as the telegram-bot's
 * AgentBackendClient). All methods call the backend REST API at
 * {@code /api/v1/agent/...}.
 * <p>
 * Connection-level failures (backend down, timeout) are wrapped in
 * {@link BackendUnavailableException} so the REPL can show a friendly message.
 */
@Component
@Slf4j
public class BackendClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BackendResponseFormatter formatter;
    private final String backendUrl;

    @Autowired
    public BackendClient(@Qualifier("backendRestClient") RestClient restClient,
                         ObjectMapper objectMapper,
                         BackendResponseFormatter formatter,
                         CliProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
        this.backendUrl = properties.getBackendUrl();
    }

    /**
     * Constructor for tests that don't need a CliProperties or formatter.
     * Creates a default formatter backed by the given ObjectMapper.
     */
    public BackendClient(RestClient restClient,
                         ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.formatter = new BackendResponseFormatter(objectMapper);
        this.backendUrl = "http://localhost:8090";
    }

    /**
     * Constructor for tests that want to inject a custom formatter.
     */
    public BackendClient(RestClient restClient,
                         ObjectMapper objectMapper,
                         BackendResponseFormatter formatter) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.formatter = formatter;
        this.backendUrl = "http://localhost:8090";
    }

    // ------------------------------------------------------------------
    // Generic transport helpers (c7: thin layer returning JsonNode)
    // ------------------------------------------------------------------

    /**
     * Execute a GET request and return the parsed JSON, or null on empty/error.
     */
    public JsonNode executeGet(String uri, Object... uriVars) {
        try {
            String json = restClient.get()
                .uri(uri, uriVars)
                .retrieve()
                .body(String.class);
            return parseJson(json);
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("GET {} failed: {}", uri, e.getMessage());
            return null;
        }
    }

    /**
     * Execute a GET request with a URI builder function (for query params).
     */
    public JsonNode executeGet(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunction) {
        try {
            String json = restClient.get()
                .uri(uriFunction)
                .retrieve()
                .body(String.class);
            return parseJson(json);
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("GET (builder) failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute a POST request with a JSON body and return the parsed JSON, or null on empty/error.
     */
    public JsonNode executePost(String uri, Object body, Object... uriVars) {
        try {
            String json = restClient.post()
                .uri(uri, uriVars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return parseJson(json);
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("POST {} failed: {}", uri, e.getMessage());
            return null;
        }
    }

    /**
     * Execute a POST request with a URI builder function (for query params).
     */
    public JsonNode executePost(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunction, Object body) {
        try {
            String json = restClient.post()
                .uri(uriFunction)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return parseJson(json);
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("POST (builder) failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Execute a DELETE request (bodiless). Returns true on success, false on error.
     */
    public boolean executeDelete(String uri, Object... uriVars) {
        try {
            restClient.delete()
                .uri(uri, uriVars)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("DELETE {} failed: {}", uri, e.getMessage());
            return false;
        }
    }

    /**
     * Execute a POST that returns a bodiless response (e.g. 204).
     * Returns true on success, false on error.
     */
    /**
     * Hermes parity (/refine): run the memory/skill background review on
     * demand with optional focus instructions. Returns the backend's JSON
     * response (accepted / reason / message).
     */
    public JsonNode refine(String sessionId, String focus) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("sessionId", sessionId);
        if (focus != null && !focus.isBlank()) {
            body.put("focus", focus.strip());
        }
        try {
            return executePost("/api/v1/agent/refine", body);
        } catch (BackendUnavailableException e) {
            throw e;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("refine failed: {}", e.getMessage());
            return objectMapper.createObjectNode().put("accepted", false)
                .put("reason", "refine request failed: " + e.getMessage());
        }
    }

    public boolean executePostBodiless(String uri, Object body, Object... uriVars) {
        try {
            restClient.post()
                .uri(uri, uriVars)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("POST (bodiless) {} failed: {}", uri, e.getMessage());
            return false;
        }
    }

    /**
     * Execute a POST with no JSON body that returns a bodiless response.
     * Separate method name to avoid varargs ambiguity with
     * {@link #executePostBodiless(String, Object, Object...)}. Sends a bare
     * POST (no content-type / body) to match bodiless endpoints.
     */
    public boolean executePostBodilessNoBody(String uri, Object... uriVars) {
        try {
            restClient.post()
                .uri(uri, uriVars)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            if (isConnectionError(e)) {
                throw new BackendUnavailableException(backendUrl, e);
            }
            log.error("POST (bodiless, no body) {} failed: {}", uri, e.getMessage());
            return false;
        }
    }

    private JsonNode parseJson(String json) throws Exception {
        if (json == null || json.isBlank()) return null;
        return objectMapper.readTree(json);
    }

    // ------------------------------------------------------------------
    // Error wrapping
    // ------------------------------------------------------------------

    private RuntimeException wrapConnectionError(Exception e) {
        if (isConnectionError(e)) {
            return new BackendUnavailableException(backendUrl, e);
        }
        return new RuntimeException(e.getMessage(), e);
    }

    private boolean isConnectionError(Throwable e) {
        if (e == null) return false;
        if (e instanceof ResourceAccessException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        return isConnectionError(e.getCause());
    }

    private String handleErr(String method, Exception e) {
        if (isConnectionError(e)) {
            throw new BackendUnavailableException(backendUrl, e);
        }
        log.error("{} failed: {}", method, e.getMessage());
        return "Error: " + e.getMessage();
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

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
                                onTool.accept("\uD83D\uDD27 " + toolName);
                            }
                            continue;
                        }

                        if ("tool_result".equalsIgnoreCase(type)) {
                            String toolName = event.path("toolName").asText("");
                            String toolResult = event.path("toolResult").asText("");
                            String preview = toolResult.length() > 200
                                ? toolResult.substring(0, 200) + "\u2026" : toolResult;
                            onTool.accept("\u2705 " + toolName + ": " + preview);
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

    private Map<String, Object> buildChatBody(String message, String sessionId, CliState state) {
        Map<String, Object> body = new LinkedHashMap<>();
        // /image attachment: reference the saved file in the outbound message
        // (same media-reference convention the Telegram gateway uses) and consume it.
        if (state != null && state.getPendingImage() != null) {
            java.nio.file.Path img = state.getPendingImage();
            state.setPendingImage(null);
            message = "[Photo: " + img.toAbsolutePath() + "]\n" + message;
        }
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
    // Session management (transport returns JsonNode/raw; formatting via formatter)
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
        if (!executePostBodilessNoBody("/api/v1/agent/session/{id}/reset", sessionId)) {
            return "Error: failed to reset session " + sessionId;
        }
        return "Session reset: " + sessionId;
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
        return executeGet("/api/v1/agent/session/{id}/context", sessionId);
    }

    public JsonNode getUsage(String sessionId) {
        return executeGet("/api/v1/agent/session/{id}/usage", sessionId);
    }

    public JsonNode listSessions(String userId) {
        JsonNode node = executeGet("/api/v1/agent/sessions/{userId}", userId);
        return node != null ? node : objectMapper.createArrayNode();
    }

    // ------------------------------------------------------------------
    // Model switching (C1)
    // ------------------------------------------------------------------

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

    public String getCurrentModel(String sessionId) {
        JsonNode node = executeGet(uriBuilder -> uriBuilder.path("/api/v1/agent/model")
            .queryParam("sessionId", sessionId)
            .build());
        if (node == null) return "No model info available.";
        return prettyPrint(node);
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
    // Cron jobs (C2) — transport + formatting split
    // ------------------------------------------------------------------

    public String listCronJobs() {
        JsonNode array = executeGet("/api/v1/agent/cron");
        return formatter.formatCronJobs(array);
    }

    public String pauseCronJob(String jobId) {
        if (!executePostBodilessNoBody("/api/v1/agent/cron/{id}/pause", jobId)) {
            return "Error: failed to pause cron job " + jobId;
        }
        return "Cron job paused: " + jobId;
    }

    public String resumeCronJob(String jobId) {
        if (!executePostBodilessNoBody("/api/v1/agent/cron/{id}/resume", jobId)) {
            return "Error: failed to resume cron job " + jobId;
        }
        return "Cron job resumed: " + jobId;
    }

    public String deleteCronJob(String jobId) {
        if (!executeDelete("/api/v1/agent/cron/{id}", jobId)) {
            return "Error: failed to delete cron job " + jobId;
        }
        return "Cron job deleted: " + jobId;
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

    // ------------------------------------------------------------------
    // Blueprints (Hermes /blueprint parity)
    // ------------------------------------------------------------------

    public JsonNode listBlueprints() {
        return executeGet("/api/v1/agent/cron/blueprints");
    }

    public String createFromBlueprint(String key, java.util.Map<String, String> values) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("values", values);
        try {
            return restClient.post()
                .uri("/api/v1/agent/cron/blueprints/{key}/create", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            return handleErr("createFromBlueprint", e);
        }
    }

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
        if (!executeDelete("/api/v1/agent/memory/{userId}/{entryId}", userId, entryId)) {
            return "Error: failed to delete memory " + entryId;
        }
        return "Memory deleted: " + entryId;
    }

    public JsonNode listAllMemory(String userId) {
        JsonNode node = executeGet("/api/v1/agent/memory/all/{userId}", userId);
        return node != null ? node : objectMapper.createArrayNode();
    }

    public JsonNode listPendingMemory(String userId) {
        JsonNode node = executeGet("/api/v1/agent/memory/pending/{userId}", userId);
        return node != null ? node : objectMapper.createArrayNode();
    }

    // ------------------------------------------------------------------
    // Approvals (C2)
    // ------------------------------------------------------------------

    public JsonNode listPendingApprovals() {
        JsonNode node = executeGet("/api/v1/agent/approvals/pending");
        return node != null ? node : objectMapper.createArrayNode();
    }

    public String approveTool(String sessionId) {
        if (!executePostBodilessNoBody("/api/v1/agent/approvals/{sessionId}/approve", sessionId)) {
            return "Error: failed to approve tool for session " + sessionId;
        }
        return "Tool approved for session: " + sessionId;
    }

    public String denyTool(String sessionId) {
        if (!executePostBodilessNoBody("/api/v1/agent/approvals/{sessionId}/deny", sessionId)) {
            return "Error: failed to deny tool for session " + sessionId;
        }
        return "Tool denied for session: " + sessionId;
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
        if (!executeDelete("/api/v1/agent/checkpoint/{id}", checkpointId)) {
            return "Error: failed to delete checkpoint " + checkpointId;
        }
        return "Checkpoint deleted: " + checkpointId;
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
    // Memory & skills (existing) — transport returns JsonNode
    // ------------------------------------------------------------------

    public JsonNode getMemory() {
        JsonNode node = executeGet("/api/v1/agent/memory");
        return node != null ? node : objectMapper.createArrayNode();
    }

    // ------------------------------------------------------------------
    // Skills hub (SIMPLIFIED Hermes parity: one GitHub repo source)
    // ------------------------------------------------------------------

    public JsonNode hubList() {
        return executeGet("/api/v1/agent/skills-hub");
    }

    public JsonNode hubSearch(String query) {
        return executeGet("/api/v1/agent/skills-hub/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
    }

    public String hubInstall(String skill, boolean overwrite) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skill", skill);
        body.put("overwrite", overwrite);
        try {
            return restClient.post()
                .uri("/api/v1/agent/skills-hub/install")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            return handleErr("hubInstall", e);
        }
    }

    public JsonNode getSkills() {
        JsonNode node = executeGet("/api/v1/agent/skills");
        return node != null ? node : objectMapper.createArrayNode();
    }

    public JsonNode listBundles() {
        JsonNode node = executeGet("/api/v1/agent/bundles");
        return node != null ? node : objectMapper.createArrayNode();
    }

    // ------------------------------------------------------------------
    // Checkpoints — formatting via formatter
    // ------------------------------------------------------------------

    public String createCheckpoint(String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description != null ? description : "Manual checkpoint");
        if (!executePostBodiless("/api/v1/agent/checkpoint", body)) {
            return "Error: failed to create checkpoint.";
        }
        return "Checkpoint created: " + description;
    }

    public String listCheckpoints() {
        JsonNode array = executeGet("/api/v1/agent/checkpoint");
        return formatter.formatCheckpoints(array);
    }

    public String restoreCheckpoint(String id) {
        if (!executePostBodilessNoBody("/api/v1/agent/checkpoint/{id}/restore", id)) {
            return "Error: failed to restore checkpoint " + id;
        }
        return "Checkpoint restored: " + id;
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
    // Health & admin — formatting via formatter
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
            String raw = restClient.get()
                .uri("/api/v1/agent/config")
                .retrieve()
                .body(String.class);
            JsonNode node = raw == null ? null : objectMapper.readTree(raw);
            return formatter.formatConfig(node);
        } catch (Exception e) {
            return handleErr("config", e);
        }
    }

    public String doctor() {
        try {
            String raw = restClient.get()
                .uri("/api/v1/agent/doctor")
                .retrieve()
                .body(String.class);
            JsonNode node = raw == null ? null : objectMapper.readTree(raw);
            return formatter.formatDoctor(node);
        } catch (Exception e) {
            return handleErr("doctor", e);
        }
    }

    public String restart() {
        if (!executePostBodilessNoBody("/api/v1/agent/restart")) {
            return "Error: failed to restart agent.";
        }
        return "Agent restarting...";
    }

    public String reloadMcp() {
        if (!executePostBodilessNoBody("/api/v1/agent/reload-mcp")) {
            return "Error: failed to reload MCP servers.";
        }
        return "MCP servers reloaded.";
    }

    public String reloadSkills() {
        if (!executePostBodilessNoBody("/api/v1/agent/reload-skills")) {
            return "Error: failed to reload skills.";
        }
        return "Skills reloaded.";
    }

    public String reloadAll() {
        if (!executePostBodilessNoBody("/api/v1/agent/reload")) {
            return "Error: failed to reload skills and MCP servers.";
        }
        return "Skills and MCP servers reloaded.";
    }

    public String diff(String leftId, String rightId) {
        JsonNode node = executeGet(uriBuilder -> uriBuilder
            .path("/api/v1/agent/diff")
            .queryParam("left", leftId)
            .queryParam("right", rightId)
            .build());
        if (node == null) return "No diff data.";
        return prettyPrint(node);
    }

    public String getCredits() {
        JsonNode node = executeGet("/api/v1/agent/credits");
        return formatter.formatCredits(node);
    }

    public String curatorStatus() {
        JsonNode node = executeGet("/api/v1/agent/curator/status");
        return formatter.formatCuratorStatus(node);
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
        if (!executePostBodilessNoBody("/api/v1/agent/curator/pause")) {
            return "Error: failed to pause curator.";
        }
        return "Curator paused.";
    }

    public String curatorResume() {
        if (!executePostBodilessNoBody("/api/v1/agent/curator/resume")) {
            return "Error: failed to resume curator.";
        }
        return "Curator resumed.";
    }

    // ── Kanban — formatting via formatter ──
    public String kanbanList() {
        JsonNode array = executeGet("/api/v1/agent/kanban");
        return formatter.formatKanban(array);
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
        if (!executePostBodilessNoBody("/api/v1/agent/kanban/done/{id}", id)) {
            return "Error: failed to mark task " + id + " done.";
        }
        return "Task " + id + " marked done.";
    }

    public String kanbanClear() {
        if (!executeDelete("/api/v1/agent/kanban")) {
            return "Error: failed to clear kanban board.";
        }
        return "Kanban board cleared.";
    }

    // ── Codex Runtime — formatting via formatter ──
    public String codexRuntimeStatus() {
        JsonNode node = executeGet("/api/v1/agent/codex-runtime");
        return formatter.formatCodexRuntime(node);
    }

    public String codexRuntimeModel(String modelName) {
        Map<String, Object> body = Map.of("model", modelName);
        if (!executePostBodiless("/api/v1/agent/codex-runtime/model", body)) {
            return "Error: failed to set codex runtime model " + modelName;
        }
        return "Codex runtime model set: " + modelName;
    }

    public String codexRuntimeReset() {
        if (!executePostBodilessNoBody("/api/v1/agent/codex-runtime/reset")) {
            return "Error: failed to reset codex runtime.";
        }
        return "Codex runtime reset.";
    }

    // ------------------------------------------------------------------
    // Agents & insights — transport returns JsonNode
    // ------------------------------------------------------------------

    public JsonNode listActiveAgents() {
        JsonNode node = executeGet("/api/v1/agent/agents");
        return node != null ? node : objectMapper.createArrayNode();
    }

    public JsonNode getInsights() {
        JsonNode node = executeGet("/api/v1/agent/insights");
        return node != null ? node : objectMapper.createObjectNode();
    }

    // ------------------------------------------------------------------
    // Pretty-print helper (delegates to formatter)
    // ------------------------------------------------------------------

    public String prettyPrint(JsonNode node) {
        return formatter.prettyPrint(node);
    }

    // ------------------------------------------------------------------
    // Backend URL (for error messages)
    // ------------------------------------------------------------------

    public String getBackendUrl() {
        return backendUrl;
    }

    // ------------------------------------------------------------------
    // P1-4: New backend methods for 15 additional slash commands
    // ------------------------------------------------------------------

    public String retry(String sessionId, String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) {
            return "No previous message to retry.";
        }
        return chat(lastMessage, sessionId);
    }

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

    public String queuePrompt(String sessionId, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("queued", prompt);
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

    public String setReasoningEffort(String sessionId, String level) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        // POST /agent/reasoning expects 'effort' (ReasoningRequest);
        // 'reasoningEffort' is the chat-stream field — they differ.
        body.put("effort", level);
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

    public String connectBrowser(String sessionId, String cdpUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("cdpUrl", cdpUrl);
        if (!executePostBodiless("/api/v1/agent/browser", body)) {
            return "Error: failed to connect browser to " + cdpUrl;
        }
        return "Browser connected: " + cdpUrl;
    }

    public JsonNode listPlugins() {
        JsonNode node = executeGet("/api/v1/mcp/servers");
        return node != null ? node : objectMapper.createArrayNode();
    }

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

    public String toggleTool(String sessionId, String toolName, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("toolName", toolName);
        String uri = enabled ? "/api/v1/agent/tools/enable" : "/api/v1/agent/tools/disable";
        if (!executePostBodiless(uri, body)) {
            return "Error: failed to " + (enabled ? "enable" : "disable") + " tool " + toolName;
        }
        return "Tool " + toolName + ": " + (enabled ? "enabled" : "disabled");
    }

    // ------------------------------------------------------------------
    // Goal management — formatting via formatter
    // ------------------------------------------------------------------

    public String setGoal(String sessionId, String goal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("goal", goal);
        if (!executePostBodiless("/api/v1/agent/goal", body)) {
            return "Error: failed to set goal.";
        }
        return "Goal set: " + goal;
    }

    public String pauseGoal(String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        if (!executePostBodiless("/api/v1/agent/goal/pause", body)) {
            return "Error: failed to pause goal.";
        }
        return "Goal paused.";
    }

    public String resumeGoal(String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        if (!executePostBodiless("/api/v1/agent/goal/resume", body)) {
            return "Error: failed to resume goal.";
        }
        return "Goal resumed.";
    }

    public String clearGoal(String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        if (!executePostBodiless("/api/v1/agent/goal/clear", body)) {
            return "Error: failed to clear goal.";
        }
        return "Goal cleared.";
    }

    public String getGoal(String sessionId) {
        JsonNode node = executeGet(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{id}/context")
            .queryParam("goal", true)
            .build(sessionId));
        return formatter.formatGoal(node);
    }

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

    // ------------------------------------------------------------------
    // P2-11: New backend methods for missing CLI commands
    // ------------------------------------------------------------------

    public String exportSession(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{id}/context", sessionId)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "No session data to export.";
            return json;
        } catch (Exception e) {
            return handleErr("exportSession", e);
        }
    }

    public JsonNode listToolsets() {
        JsonNode node = executeGet("/v1/toolsets");
        return node != null ? node : objectMapper.createArrayNode();
    }

    public String toggleToolset(String toolsetName, boolean enabled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toolset", toolsetName);
        body.put("enabled", enabled);
        String uri = enabled ? "/v1/toolsets/" + toolsetName + "/enable" : "/v1/toolsets/" + toolsetName + "/disable";
        if (!executePostBodiless(uri, body)) {
            return "Error: failed to " + (enabled ? "enable" : "disable") + " toolset " + toolsetName;
        }
        return "Toolset " + toolsetName + ": " + (enabled ? "enabled" : "disabled");
    }

    public String handoffModel(String sessionId, String model, String provider) {
        String result = switchModel(sessionId, model, provider);
        return "Handoff: " + result;
    }

    public String getPlan(String sessionId) {
        JsonNode node = executeGet(uriBuilder -> uriBuilder.path("/api/v1/agent/session/{id}/context")
            .queryParam("plan", true)
            .build(sessionId));
        return formatter.formatPlan(node);
    }

    public String uploadDebugReport() {
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/debug-report")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of())
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return "Debug report uploaded.";
            JsonNode node = objectMapper.readTree(json);
            String link = node.path("link").asText(null);
            if (link != null && !link.isBlank()) {
                return "Debug report uploaded. Shareable link: " + link;
            }
            return node.path("message").asText("Debug report uploaded.");
        } catch (Exception e) {
            return handleErr("uploadDebugReport", e);
        }
    }
}