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
 * Per-domain delegate covering memory endpoints:
 * memory listing, pending-memory approval workflow, approval toggle,
 * per-user memory listing/delete/store.
 */
@Service
@Slf4j
public class MemoryApiClient extends BaseBackendClient {

    public MemoryApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    // ------------------------------------------------------------------
    // Simple memory access
    // ------------------------------------------------------------------

    public JsonNode getMemory() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("getMemory failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    public JsonNode listAllMemory(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory/all/{userId}", userId)
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listAllMemory failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    public boolean storeMemory(String userId, String text) {
        Map<String, Object> body = body();
        body.put("userId", userId != null ? userId : "default");
        body.put("fact", text);
        body.put("category", "user");
        body.put("target", "memory");
        try {
            restClient.post()
                .uri("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("storeMemory failed: {}", e.getMessage());
            return false;
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
            log.warn("deleteMemory failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Pending-memory approval workflow
    // ------------------------------------------------------------------

    public JsonNode listPendingMemory(String userId) {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/memory/pending/{userId}", userId)
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listPendingMemory failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    public boolean approvePendingMemory(String userId, String id) {
        Map<String, Object> body = body();
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
            log.warn("approvePendingMemory failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean rejectPendingMemory(String userId, String id) {
        Map<String, Object> body = body();
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
            log.warn("rejectPendingMemory failed: {}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Memory approval toggle
    // ------------------------------------------------------------------

    public void setMemoryApproval(boolean enabled) {
        Map<String, Object> body = body();
        body.put("enabled", enabled);
        try {
            restClient.post()
                .uri("/api/v1/agent/memory/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("setMemoryApproval failed: {}", e.getMessage());
        }
    }

    public boolean isMemoryApprovalEnabled() {
        try {
            String responseJson = restClient.get()
                .uri("/api/v1/agent/memory/approval")
                .retrieve()
                .body(String.class);
            if (responseJson == null || responseJson.isBlank()) {
                return false;
            }
            return Boolean.parseBoolean(responseJson.trim());
        } catch (Exception e) {
            log.warn("isMemoryApprovalEnabled failed: {}", e.getMessage());
            return false;
        }
    }
}