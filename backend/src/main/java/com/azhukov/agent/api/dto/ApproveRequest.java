package com.azhukov.agent.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/** Approval request body. {@code all} defaults to false when omitted. */
public record ApproveRequest(Boolean all, UUID requestId) {
    public boolean isAll() { return all != null && all; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown approval field: " + name);
    }
}
