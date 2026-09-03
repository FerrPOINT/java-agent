package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Per-domain delegate covering skill-related endpoints:
 * skill listing, skill reload, bundle listing/install/uninstall and the
 * "reload all" (skills + MCP) endpoint.
 */
@Service
@Slf4j
public class SkillApiClient extends BaseBackendClient {

    public SkillApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    // ------------------------------------------------------------------
    // Skills
    // ------------------------------------------------------------------

    public JsonNode getSkills() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/skills")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("getSkills failed: {}", e.getMessage());
            return arrayNode();
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
            log.warn("reloadSkills failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /** Reload both skills and MCP servers. */
    public String reloadAll() {
        try {
            restClient.post()
                .uri("/api/v1/agent/reload")
                .retrieve()
                .toBodilessEntity();
            return "Skills and MCP servers reloaded.";
        } catch (Exception e) {
            log.warn("reloadAll failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * rev-105: Hermes parity (gateway/run.py:18055+) — build a skill
     * slash-command invocation message. Returns the activation text to
     * submit as a user turn, or null if the command does not resolve.
     */
    public String invokeSkill(String command, String userInstruction, String sessionId) {
        try {
            Map<String, Object> body = body();
            body.put("command", command);
            body.put("userInstruction", userInstruction == null ? "" : userInstruction);
            if (sessionId != null) body.put("sessionId", sessionId);
            String json = restClient.post()
                .uri("/api/v1/agent/skill-invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            if (parsed != null && parsed.has("resolved") && parsed.get("resolved").asBoolean()) {
                return parsed.get("message").asText(null);
            }
            return null;
        } catch (Exception e) {
            log.debug("invokeSkill failed for /{}: {}", command, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Bundles
    // ------------------------------------------------------------------

    public JsonNode listBundles() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/bundles")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listBundles failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    public String installBundle(String bundleName) {
        try {
            return restClient.post()
                .uri("/api/v1/agent/bundles/install")
                .body(Map.of("bundleName", bundleName))
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.warn("installBundle failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String uninstallBundle(String bundleName) {
        try {
            return restClient.post()
                .uri("/api/v1/agent/bundles/uninstall")
                .body(Map.of("bundleName", bundleName))
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.warn("uninstallBundle failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}