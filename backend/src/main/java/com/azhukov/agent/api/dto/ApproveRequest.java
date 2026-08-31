package com.azhukov.agent.api.dto;

/** Approval request body. {@code all} defaults to false when omitted. */
public record ApproveRequest(Boolean all, String scope) {
    public boolean isAll() { return all != null && all; }
}