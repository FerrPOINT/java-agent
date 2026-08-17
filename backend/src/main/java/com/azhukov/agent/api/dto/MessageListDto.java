package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Paginated message list envelope returned by the session messages endpoint.
 */
public record MessageListDto(
    @JsonProperty("object") String object,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("data") List<MessageDto> data,
    @JsonProperty("limit") int limit,
    @JsonProperty("offset") int offset
) {}