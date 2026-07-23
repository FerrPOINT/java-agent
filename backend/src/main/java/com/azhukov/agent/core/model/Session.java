package com.azhukov.agent.core.model;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record Session(
    UUID id,
    String userId,
    String title,
    String modelProvider,
    String modelName,
    String systemPrompt,
    Map<String, String> metadata
) {
    public Session {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    public static Session create(String userId, String modelProvider, String modelName) {
        return new Session(UUID.randomUUID(), userId, null, modelProvider, modelName, null, Map.of());
    }

    public Session withMetadata(String key, String value) {
        Map<String, String> updated = new java.util.HashMap<>(metadata);
        updated.put(key, value);
        return new Session(id, userId, title, modelProvider, modelName, systemPrompt, Map.copyOf(updated));
    }
}
