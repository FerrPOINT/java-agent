package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages pending tool-approval requests with support for approve, deny, and
 * automatic timeout-based denial.
 *
 * <p>Each session can have at most one pending approval at a time.  When a
 * new request is made for a session that already has a pending (undecided)
 * approval, the old one is replaced.
 */
@Component
public class ApprovalQueue {

    /** Default auto-deny timeout: 5 minutes. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Map<UUID, PendingApproval> pending = new ConcurrentHashMap<>();

    /**
     * Creates a pending approval request for the given session and tool call.
     *
     * @param sessionId the session requesting approval
     * @param call      the tool call that requires approval
     * @param reason    human-readable reason for the approval request
     * @return the created pending approval
     */
    public PendingApproval request(UUID sessionId, ToolCall call, String reason) {
        return request(sessionId, call, reason, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a pending approval request with a custom auto-deny timeout.
     *
     * @param sessionId     the session requesting approval
     * @param call          the tool call that requires approval
     * @param reason        human-readable reason for the approval request
     * @param timeoutDuration auto-deny duration (null uses default)
     * @return the created pending approval
     */
    public PendingApproval request(UUID sessionId, ToolCall call, String reason, Duration timeoutDuration) {
        Instant now = Instant.now();
        Duration timeout = timeoutDuration != null ? timeoutDuration : DEFAULT_TIMEOUT;
        Instant expiresAt = now.plus(timeout);
        PendingApproval p = new PendingApproval(
            UUID.randomUUID(), sessionId, call, reason, now, false, false, null, expiresAt);
        pending.put(sessionId, p);
        return p;
    }

    /**
     * Returns the pending approval for the given session, or {@code null} if none.
     * Auto-denies if the approval has expired.
     */
    public PendingApproval getPending(UUID sessionId) {
        PendingApproval p = pending.get(sessionId);
        if (p != null && !p.approved() && !p.denied() && p.expiresAt() != null && Instant.now().isAfter(p.expiresAt())) {
            PendingApproval denied = new PendingApproval(
                p.requestId(), p.sessionId(), p.call(), p.reason(), p.requestedAt(),
                false, true, "Auto-denied: timeout", p.expiresAt());
            pending.put(sessionId, denied);
            return denied;
        }
        return p;
    }

    /**
     * Approves the pending approval for the given session.
     *
     * @param sessionId the session whose approval to act on
     * @param decision  "approve" or "once" to approve; any other value denies
     * @param note      optional note
     * @return the updated approval, or {@code null} if none pending
     */
    public PendingApproval approve(UUID sessionId, String decision, String note) {
        PendingApproval p = pending.get(sessionId);
        if (p == null) return null;
        boolean approved = "approve".equalsIgnoreCase(decision) || "once".equalsIgnoreCase(decision);
        PendingApproval updated = new PendingApproval(
            p.requestId(), p.sessionId(), p.call(), p.reason(), p.requestedAt(),
            approved, !approved, note, p.expiresAt());
        pending.put(sessionId, updated);
        return updated;
    }

    /**
     * Explicitly denies the pending approval for the given session.
     *
     * @param sessionId the session whose approval to deny
     * @param note      optional note explaining the denial
     * @return the updated approval, or {@code null} if none pending
     */
    public PendingApproval deny(UUID sessionId, String note) {
        PendingApproval p = pending.get(sessionId);
        if (p == null) return null;
        PendingApproval updated = new PendingApproval(
            p.requestId(), p.sessionId(), p.call(), p.reason(), p.requestedAt(),
            false, true, note, p.expiresAt());
        pending.put(sessionId, updated);
        return updated;
    }

    /**
     * Returns {@code true} if the approval for the given session has been approved.
     */
    public boolean isApproved(UUID sessionId) {
        PendingApproval p = getPending(sessionId);
        return p != null && p.approved();
    }

    /**
     * Returns {@code true} if the approval for the given session is still pending
     * (neither approved nor denied).
     */
    public boolean isPending(UUID sessionId) {
        PendingApproval p = getPending(sessionId);
        return p != null && !p.approved() && !p.denied();
    }

    /**
     * Returns {@code true} if the approval for the given session has been denied.
     */
    public boolean isDenied(UUID sessionId) {
        PendingApproval p = getPending(sessionId);
        return p != null && p.denied();
    }

    /**
     * Returns a list of all pending (undecided) approvals across all sessions.
     */
    public List<PendingApproval> getPendingApprovals() {
        return pending.values().stream()
            .filter(p -> !p.approved() && !p.denied())
            .collect(Collectors.toList());
    }

    /**
     * Auto-denies all pending approvals that have exceeded their timeout.
     *
     * @param timeout the timeout duration (unused — each approval has its own expiresAt)
     */
    public void timeout(Duration timeout) {
        Instant now = Instant.now();
        for (Map.Entry<UUID, PendingApproval> entry : pending.entrySet()) {
            PendingApproval p = entry.getValue();
            if (!p.approved() && !p.denied() && p.expiresAt() != null && now.isAfter(p.expiresAt())) {
                PendingApproval denied = new PendingApproval(
                    p.requestId(), p.sessionId(), p.call(), p.reason(), p.requestedAt(),
                    false, true, "Auto-denied: timeout", p.expiresAt());
                pending.put(entry.getKey(), denied);
            }
        }
    }

    /**
     * Clears the approval for the given session.
     */
    public void clear(UUID sessionId) {
        pending.remove(sessionId);
    }

    /**
     * A pending approval request.
     *
     * @param requestId   unique ID for this approval request
     * @param sessionId   the session that requested approval
     * @param call        the tool call requiring approval
     * @param reason      human-readable reason
     * @param requestedAt when the request was created
     * @param approved    whether the request was approved
     * @param denied      whether the request was denied
     * @param note        optional note from the approver/denier
     * @param expiresAt   when this request auto-denies (null = no timeout)
     */
    public record PendingApproval(
        UUID requestId,
        UUID sessionId,
        ToolCall call,
        String reason,
        Instant requestedAt,
        boolean approved,
        boolean denied,
        String note,
        Instant expiresAt
    ) {}
}