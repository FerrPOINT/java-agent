package com.azhukov.agent.core.model;

import java.util.Objects;

public record ToolCall(
    String id,
    String name,
    String arguments
) {
    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
    }
}
