package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared base for per-domain backend API client delegates.
 * Provides the {@link RestClient}, {@link ObjectMapper} and a couple of
 * common helpers used by every concrete client.
 */
@Slf4j
public abstract class BaseBackendClient {

    protected final RestClient restClient;
    protected final ObjectMapper objectMapper;

    protected BaseBackendClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /** Empty-array fallback for GET endpoints that return lists. */
    protected JsonNode arrayNode() {
        return objectMapper.createArrayNode();
    }

    /** Empty-object fallback for GET endpoints that return a single object. */
    protected JsonNode objectNode() {
        return objectMapper.createObjectNode();
    }

    /** Parse a JSON string, returning {@code null} on failure or blank input. */
    protected JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("readTree failed: {}", e.getMessage());
            return null;
        }
    }

    /** Convenience for building JSON request bodies with stable key order. */
    protected static Map<String, Object> body() {
        return new LinkedHashMap<>();
    }
}