package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record CheckpointDiffDto(
    UUID left,
    UUID right,
    String scope,
    JsonNode changed,
    JsonNode added,
    JsonNode removed,
    int leftFileCount,
    int rightFileCount,
    String error
) {}