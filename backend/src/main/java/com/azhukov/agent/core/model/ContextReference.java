package com.azhukov.agent.core.model;

public record ContextReference(
    ReferenceType type,
    String source,
    String displayName,
    String error
) {
    public boolean success() { return error == null || error.isEmpty(); }
}
