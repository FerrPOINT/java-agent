package com.azhukov.agent.api.dto;

/** Denial request body. {@code all} defaults to false when omitted. */
public record DenyRequest(Boolean all) {
    public boolean isAll() { return all != null && all; }
}