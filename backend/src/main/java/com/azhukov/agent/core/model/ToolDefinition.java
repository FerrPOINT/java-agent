package com.azhukov.agent.core.model;

import java.util.Map;
import java.util.Objects;

public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> parameters
) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
    }
}
