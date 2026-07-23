package com.azhukov.agent.core.model;

import java.util.Objects;
import java.util.UUID;

public record Session(
    UUID id,
    String userId,
    String title,
    String modelProvider,
    String modelName,
    String systemPrompt
) {
    public Session {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
    }

    public static Session create(String userId, String modelProvider, String modelName) {
        return new Session(UUID.randomUUID(), userId, null, modelProvider, modelName, null);
    }
}
