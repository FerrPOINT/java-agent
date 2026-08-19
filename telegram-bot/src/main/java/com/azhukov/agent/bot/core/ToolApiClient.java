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
 * Per-domain delegate covering tool-related endpoints:
 * MCP reload, active agents listing and the Kanban task board CRUD.
 */
@Service
@Slf4j
public class ToolApiClient extends BaseBackendClient {

    public ToolApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    // ------------------------------------------------------------------
    // MCP / agents
    // ------------------------------------------------------------------

    public String reloadMcp() {
        try {
            restClient.post()
                .uri("/api/v1/agent/reload-mcp")
                .retrieve()
                .toBodilessEntity();
            return "MCP servers reloaded.";
        } catch (Exception e) {
            log.warn("reloadMcp failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public JsonNode listActiveAgents() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/agents")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listActiveAgents failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    // ------------------------------------------------------------------
    // Kanban CRUD
    // ------------------------------------------------------------------

    public JsonNode getKanban() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/kanban")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("getKanban failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    public JsonNode addKanbanTask(String text) {
        Map<String, Object> body = body();
        body.put("text", text);
        try {
            String json = restClient.post()
                .uri("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            if (json == null || json.isBlank()) return null;
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("addKanbanTask failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean doneKanbanTask(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/kanban/done/{id}", id)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("doneKanbanTask failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean clearKanban() {
        try {
            restClient.delete()
                .uri("/api/v1/agent/kanban")
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("clearKanban failed: {}", e.getMessage());
            return false;
        }
    }
}