package com.azhukov.agent.api.dto;

import java.util.UUID;

public record CompressRequest(
    UUID sessionId,
    String focusTopic,
    Integer keepLastN
) {
    // Backward-compatible constructor
    public CompressRequest(UUID sessionId, String focus) {
        this(sessionId, focus, null);
    }

    // Convenience accessor for backward compatibility
    public String focus() {
        return focusTopic;
    }
}