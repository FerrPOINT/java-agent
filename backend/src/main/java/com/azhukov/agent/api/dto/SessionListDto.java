package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Generic paginated list envelope used by the session list / messages endpoints.
 * Preserves the legacy JSON shape (object, data, limit, offset, has_more, session_id).
 */
public record SessionListDto(
    @JsonProperty("object") String object,
    @JsonProperty("data") List<?> data,
    @JsonProperty("limit") int limit,
    @JsonProperty("offset") int offset,
    @JsonProperty("has_more") boolean hasMore
) {}