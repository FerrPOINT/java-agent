package com.azhukov.agent.shared.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Objects;
import java.util.UUID;

/** One decision request for the backend, CLI and Telegram. */
public record ToolApprovalDecision(Action action, boolean all, UUID requestId, String note) {
    public static final String PATH = "/agent/approvals/decisions";

    public enum Action { APPROVE, DENY }

    public ToolApprovalDecision {
        Objects.requireNonNull(action, "Approval action required");
        if (all && requestId != null) {
            throw new IllegalArgumentException("Choose all or one approval request");
        }
    }

    public static ToolApprovalDecision fromCommand(Action action, String arguments) {
        String target = arguments == null ? "" : arguments.strip();
        if (target.isEmpty() || "all".equalsIgnoreCase(target)) {
            return new ToolApprovalDecision(action, !target.isEmpty(), null, null);
        }
        UUID requestId = UUID.fromString(target);
        if (!requestId.toString().equalsIgnoreCase(target)) {
            throw new IllegalArgumentException("Invalid approval request ID");
        }
        return new ToolApprovalDecision(action, false, requestId, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown approval field: " + name);
    }
}
