package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

public record CompressRequest(UUID sessionId, String focusTopic, Integer keepLastN) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown compression field: " + name);
    }
}
