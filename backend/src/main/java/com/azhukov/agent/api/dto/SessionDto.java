package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * DTO for a session resource (the {@code "object":"session"} representation returned
 * by the session CRUD endpoints). Preserves the legacy snake_case JSON keys.
 *
 * @param object   always {@code "session"}
 * @param id       session id
 * @param userId   owner user id
 * @param title    session title
 * @param model    model name
 * @param source   always {@code "api_server"}
 */
public record SessionDto(
    @JsonProperty("object") String object,
    @JsonProperty("id") String id,
    @JsonProperty("user_id") String userId,
    @JsonProperty("title") String title,
    @JsonProperty("model") String model,
    @JsonProperty("source") String source
) {}