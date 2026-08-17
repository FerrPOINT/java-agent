package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for a single message in a session, returned by the session messages endpoint.
 * Preserves the legacy snake_case JSON keys. Optional fields are omitted when null
 * (matching the old {@code LinkedHashMap}-based response).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageDto(
    @JsonProperty("id") String id,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("role") String role,
    @JsonProperty("content") String content,
    @JsonProperty("tool_name") String toolCallName,
    @JsonProperty("tool_call_id") String toolCallId,
    @JsonProperty("turn_index") Integer turnIndex,
    @JsonProperty("timestamp") String timestamp
) {}