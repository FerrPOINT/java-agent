package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code DELETE /api/v2/sessions/{id}} — preserves the legacy JSON shape.
 */
public record SessionDeletedDto(
    @JsonProperty("object") String object,
    @JsonProperty("id") String id,
    @JsonProperty("deleted") boolean deleted
) {}