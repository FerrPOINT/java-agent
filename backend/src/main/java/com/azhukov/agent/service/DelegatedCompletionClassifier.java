package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Hermes {@code _classify_completion_target} parity (gateway/run_notifications.py).
 *
 * <p>Classifies an async delegated-completion target BEFORE spending a delivery
 * attempt on it:
 * <ul>
 *   <li>{@code deliver} — parent session is live, or compression-rotated with a
 *       live continuation (the tip); the resolver still retargets at send time;</li>
 *   <li>{@code terminal} — the parent is gone for good (unknown, or closed by a
 *       user boundary such as a new-session reset): drop the durable delivery
 *       row instead of falsely acking or retrying forever;</li>
 *   <li>{@code retry} — DB unavailable or rotation mid-flight (continuation not
 *       visible yet): release the claim, retry later.</li>
 * </ul>
 */
@Component
@Slf4j
public class DelegatedCompletionClassifier {

    /** User-initiated boundary end reasons (Hermes _USER_BOUNDARY_END_REASONS). */
    private static final List<String> USER_BOUNDARY_END_REASONS =
        List.of("new_session", "user_exit", "session_switch");

    public enum Verdict { DELIVER, TERMINAL, RETRY }

    private final ObjectProvider<SessionRepository> sessionRepositoryProvider;

    public DelegatedCompletionClassifier(ObjectProvider<SessionRepository> sessionRepositoryProvider) {
        this.sessionRepositoryProvider = sessionRepositoryProvider;
    }

    /** Classify the delivery target for a delegated run's parent session. */
    public Verdict classify(UUID parentSessionId) {
        if (parentSessionId == null) {
            return Verdict.TERMINAL;
        }
        SessionRepository repository = sessionRepositoryProvider == null
            ? null : sessionRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return Verdict.RETRY;
        }
        SessionEntity parent;
        try {
            parent = repository.findById(parentSessionId).orElse(null);
        } catch (Exception e) {
            log.debug("Completion target parent lookup failed for {}: {}", parentSessionId, e.getMessage());
            return Verdict.RETRY;
        }
        if (parent == null) {
            return Verdict.TERMINAL;
        }
        if (parent.getEndReason() == null) {
            return Verdict.DELIVER;
        }
        String endReason = parent.getEndReason();
        if (!"compression".equals(endReason)) {
            // Only a USER-closed session is unreachable; idle/timeout ends stay
            // routable and the resolver retargets at send time.
            return USER_BOUNDARY_END_REASONS.contains(endReason) ? Verdict.TERMINAL : Verdict.DELIVER;
        }
        // Compression rotation: deliver only through the live continuation tip.
        List<SessionEntity> children;
        try {
            children = repository.findByParentSessionIdOrderByCreatedAtDesc(parentSessionId);
        } catch (Exception e) {
            log.debug("Completion tip lookup failed for {}: {}", parentSessionId, e.getMessage());
            return Verdict.RETRY;
        }
        if (children.isEmpty()) {
            // Rotation mid-flight: continuation not visible yet. Retry, don't drop.
            return Verdict.RETRY;
        }
        SessionEntity tip = children.get(0);
        return tip.getEndReason() == null ? Verdict.DELIVER : Verdict.RETRY;
    }
}
