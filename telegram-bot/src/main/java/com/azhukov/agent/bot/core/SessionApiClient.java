package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Per-domain delegate covering session lifecycle endpoints:
 * reset, context, usage, session listing, compress, undo, checkpoints,
 * branching, steer, goals and subgoals.
 */
@Service
@Slf4j
public class SessionApiClient extends BaseBackendClient {

    public SessionApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    // ------------------------------------------------------------------
    // Session management
    // ------------------------------------------------------------------

    public boolean resetSession(String sessionId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/session/{sessionId}/reset", sessionId)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("resetSession failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public JsonNode getContext(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{sessionId}/context", sessionId)
                .retrieve()
                .body(String.class);
            return readTree(json);
        } catch (Exception e) {
            log.warn("getContext failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** Live session transcript from the backend (oldest → newest). */
    public JsonNode getMessages(String sessionId, int limit) {
        try {
            String json = restClient.get()
                .uri("/api/v2/sessions/{sessionId}/messages?limit={limit}", sessionId, limit)
                .retrieve()
                .body(String.class);
            JsonNode root = readTree(json);
            return root.path("data");
        } catch (Exception e) {
            log.warn("getMessages failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public JsonNode getUsage(String sessionId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/session/{sessionId}/usage", sessionId)
                .retrieve()
                .body(String.class);
            return readTree(json);
        } catch (Exception e) {
            log.warn("getUsage failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public JsonNode listSessionsByUser(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/sessions/{userId}", userId)
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listSessionsByUser failed for userId={}: {}", userId, e.getMessage());
            return arrayNode();
        }
    }

    // ------------------------------------------------------------------
    // Compress & undo
    // ------------------------------------------------------------------

    public String compressSession(String sessionId, String focus) {
        Map<String, Object> body = body();
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
            log.warn("compressSession failed for sessionId={}: {}", sessionId, e.getMessage());
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
            log.warn("undoTurns failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String compressSessionPartial(String sessionId, int keepLastN) {
        Map<String, Object> body = body();
        body.put("keepLastN", keepLastN);
        try {
            restClient.post()
                .uri("/api/v1/agent/session/{sessionId}/compress", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return "Context compressed (kept last " + keepLastN + " exchanges).";
        } catch (Exception e) {
            log.warn("compressSessionPartial failed for sessionId={}: {}", sessionId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Checkpoints
    // ------------------------------------------------------------------

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
                sb.append(String.format("- %s | %s | %d files\n", id, desc, files));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("listCheckpoints failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String restoreCheckpoint(String checkpointId) {
        try {
            restClient.post()
                .uri("/api/v1/agent/checkpoint/{id}/restore", checkpointId)
                .retrieve()
                .toBodilessEntity();
            return "Checkpoint restored: " + checkpointId;
        } catch (Exception e) {
            log.warn("restoreCheckpoint failed for id={}: {}", checkpointId, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String createCheckpoint(String description) {
        Map<String, Object> body = body();
        body.put("description", description);
        try {
            restClient.post()
                .uri("/api/v1/agent/checkpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return "Checkpoint created: " + description;
        } catch (Exception e) {
            log.warn("createCheckpoint failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Branch
    // ------------------------------------------------------------------

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
            log.warn("branchSession failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Stop (cancel active backend turn)
    // ------------------------------------------------------------------

    /** Cancel the active backend turn for the session (Hermes /stop parity). */
    public boolean stop(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = body();
            body.put("sessionId", sessionId);
            restClient.post()
                .uri("/api/v1/agent/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("stop failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Steer
    // ------------------------------------------------------------------

    public boolean steer(String sessionId, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = body();
            body.put("sessionId", sessionId);
            body.put("text", text);
            String result = restClient.post()
                .uri("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null && result.contains("\"accepted\":true");
        } catch (Exception e) {
            log.warn("steer failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Goals & subgoals
    // ------------------------------------------------------------------

    public boolean clearGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/goal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("clearGoal failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean setGoal(String sessionId, String goal) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId, "goal", goal))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("setGoal failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean pauseGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/goal/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("pauseGoal failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean resumeGoal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/goal/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("resumeGoal failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean appendSubgoal(String sessionId, String subgoal) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId, "subgoal", subgoal, "append", "true"))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("appendSubgoal failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public boolean clearSubgoals(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            restClient.post()
                .uri("/api/v1/agent/subgoal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("sessionId", sessionId))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("clearSubgoals failed for sessionId={}: {}", sessionId, e.getMessage());
            return false;
        }
    }
}