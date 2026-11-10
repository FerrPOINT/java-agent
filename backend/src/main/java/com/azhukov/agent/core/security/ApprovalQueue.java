package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages pending tool-approval requests with support for approve, deny, and
 * automatic timeout-based denial.
 *
 * <p>Each session can have at most one pending approval at a time.  When a
 * new request is made for a session that already has a pending (undecided)
 * approval, the old one is superseded — explicitly marked as superseded
 * rather than silently dropped (HERMES-SYNC Bug 2: approval coalesce).
 */
@Component
public class ApprovalQueue {

    /** Default auto-deny timeout: 5 minutes. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Map<UUID, Request> pending = new ConcurrentHashMap<>();

    private static final class Request {
        private volatile PendingApproval approval;
        private final CountDownLatch decided = new CountDownLatch(1);

        Request(PendingApproval approval) {
            this.approval = approval;
        }
    }

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
        Request replacement = new Request(p);
        for (;;) {
            Request current = pending.putIfAbsent(sessionId, replacement);
            if (current == null) return p;
            synchronized (current) {
                if (pending.replace(sessionId, current, replacement)) {
                    current.decided.countDown();
                    return p;
                }
            }
        }
    }

    /** Return the current request, auto-denying it when its deadline elapses. */
    public PendingApproval getPending(UUID sessionId) {
        for (;;) {
            Request current = pending.get(sessionId);
            if (current == null) return null;
            synchronized (current) {
                if (pending.get(sessionId) != current) continue;
                PendingApproval approval = current.approval;
                if (isUndecided(approval) && isExpired(approval)) {
                    decide(current, false, "Auto-denied: timeout");
                }
                return current.approval;
            }
        }
    }

    public PendingApproval approve(UUID sessionId, String decision, String note) {
        return resolve(sessionId, "approve".equalsIgnoreCase(decision) || "once".equalsIgnoreCase(decision), note);
    }

    public PendingApproval deny(UUID sessionId, String note) {
        return resolve(sessionId, false, note);
    }

    private PendingApproval resolve(UUID sessionId, boolean approved, String note) {
        Request current = pending.get(sessionId);
        if (current == null) return null;
        synchronized (current) {
            if (pending.get(sessionId) != current) return null;
            decide(current, approved, note);
            return current.approval;
        }
    }

    /**
     * Persist a decision for the exact pending request before publishing it to its waiter.
     * A failed persistence callback leaves the request pending.
     */
    public boolean resolvePending(PendingApproval expected, boolean approved, Runnable persistDecision) {
        UUID sessionId = expected.sessionId();
        Request current = pending.get(sessionId);
        if (current == null) return false;
        synchronized (current) {
            if (pending.get(sessionId) != current || current.approval != expected
                    || !isUndecided(expected) || isExpired(expected)) {
                return false;
            }
            persistDecision.run();
            // The wait can end while the response is being recorded. Publish only
            // if this request is still current and within its deadline.
            Request resolved = pending.computeIfPresent(sessionId, (id, request) -> {
                if (request == current && !isExpired(expected)) {
                    decide(request, approved, null);
                }
                return request;
            });
            return resolved == current && current.approval != expected;
        }
    }

    private static void decide(Request request, boolean approved, String note) {
        PendingApproval p = request.approval;
        request.approval = new PendingApproval(p.requestId(), p.sessionId(), p.call(), p.reason(),
            p.requestedAt(), approved, !approved, note, p.expiresAt());
        request.decided.countDown();
    }

    private static boolean isUndecided(PendingApproval p) {
        return !p.approved() && !p.denied() && !p.superseded();
    }

    private static boolean isExpired(PendingApproval p) {
        return p.expiresAt() != null && !Instant.now().isBefore(p.expiresAt());
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
     * (neither approved nor denied nor superseded).
     */
    public boolean isPending(UUID sessionId) {
        PendingApproval p = getPending(sessionId);
        return p != null && !p.approved() && !p.denied() && !p.superseded();
    }

    /**
     * Returns {@code true} if the approval for the given session has been denied.
     */
    public boolean isDenied(UUID sessionId) {
        PendingApproval p = getPending(sessionId);
        return p != null && p.denied();
    }

    /**
     * HERMES-SYNC Bug 2: Returns {@code true} if the approval for the given session
     * has been superseded by a newer approval request.
     */
    public boolean isSuperseded(UUID sessionId) {
        Request request = pending.get(sessionId);
        return request != null && request.approval.superseded();
    }

    /**
     * Returns a list of all pending (undecided, non-superseded) approvals across all sessions.
     */
    public List<PendingApproval> getPendingApprovals() {
        return pending.values().stream().map(request -> request.approval)
            .filter(ApprovalQueue::isUndecided)
            .collect(Collectors.toList());
    }

    /** Auto-deny expired pending approvals. Each request carries its own deadline. */
    public void timeout(Duration timeout) {
        pending.forEach((sessionId, request) -> {
            if (isExpired(request.approval)) getPending(sessionId);
        });
    }

    public void clear(UUID sessionId) {
        Request current = pending.remove(sessionId);
        if (current != null) current.decided.countDown();
    }

    public boolean awaitDecision(UUID sessionId, long timeoutMs) {
        Request current = pending.get(sessionId);
        if (current == null) return true;
        Instant expiresAt = current.approval.expiresAt();
        long waitMs = expiresAt == null ? timeoutMs
            : Math.min(timeoutMs, Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis()));
        try {
            return current.decided.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
     * @param superseded  whether this request was superseded by a newer one (HERMES-SYNC Bug 2)
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
        Instant expiresAt,
        boolean superseded
    ) {
        /** Backward-compatible constructor — superseded defaults to false. */
        public PendingApproval(
            UUID requestId, UUID sessionId, ToolCall call, String reason,
            Instant requestedAt, boolean approved, boolean denied, String note, Instant expiresAt
        ) {
            this(requestId, sessionId, call, reason, requestedAt, approved, denied, note, expiresAt, false);
        }
    }
}