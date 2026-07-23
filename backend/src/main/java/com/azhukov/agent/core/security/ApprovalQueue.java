package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApprovalQueue {

    private final Map<UUID, PendingApproval> pending = new ConcurrentHashMap<>();

    public PendingApproval request(UUID sessionId, ToolCall call, String reason) {
        PendingApproval p = new PendingApproval(
            UUID.randomUUID(), sessionId, call, reason, Instant.now(), false, null);
        pending.put(sessionId, p);
        return p;
    }

    public PendingApproval getPending(UUID sessionId) {
        return pending.get(sessionId);
    }

    public PendingApproval approve(UUID sessionId, String decision, String note) {
        PendingApproval p = pending.get(sessionId);
        if (p == null) return null;
        PendingApproval updated = new PendingApproval(
            p.requestId(), p.sessionId(), p.call(), p.reason(), p.requestedAt(),
            "approve".equalsIgnoreCase(decision) || "once".equalsIgnoreCase(decision),
            note);
        pending.put(sessionId, updated);
        return updated;
    }

    public void clear(UUID sessionId) {
        pending.remove(sessionId);
    }

    public record PendingApproval(
        UUID requestId,
        UUID sessionId,
        ToolCall call,
        String reason,
        Instant requestedAt,
        boolean approved,
        String note
    ) {}
}
