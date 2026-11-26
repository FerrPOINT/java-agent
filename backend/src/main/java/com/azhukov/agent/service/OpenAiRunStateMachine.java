package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import com.azhukov.agent.persistence.repository.OpenAiRunEventRepository;
import com.azhukov.agent.persistence.repository.OpenAiRunStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * WP-6 (docs/35): durable OpenAI Runs state machine + append-only event log.
 *
 * <p>Contract-first transition table (OpenAI Runs semantics): queued →
 * in_progress → requires_action ⇄ in_progress → completed|failed; any
 * non-terminal → cancelled (repeated cancel is a no-op returning the same
 * terminal state); queued/in_progress → expired on TTL. Terminal states are
 * idempotent — no transition leaves them. Every accepted transition and every
 * emitted event is persisted, so getRun and SSE replay survive restarts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiRunStateMachine {

    public static final String QUEUED = "queued";
    public static final String IN_PROGRESS = "in_progress";
    public static final String REQUIRES_ACTION = "requires_action";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String EXPIRED = "expired";

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        QUEUED, Set.of(IN_PROGRESS, CANCELLED, EXPIRED, FAILED),
        IN_PROGRESS, Set.of(REQUIRES_ACTION, COMPLETED, FAILED, CANCELLED),
        REQUIRES_ACTION, Set.of(IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED, FAILED),
        COMPLETED, Set.of(),
        FAILED, Set.of(),
        CANCELLED, Set.of(),
        EXPIRED, Set.of());

    private final ObjectProvider<OpenAiRunStateRepository> stateRepositoryProvider;
    private final ObjectProvider<OpenAiRunEventRepository> eventRepositoryProvider;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public static boolean isTerminal(String state) {
        return COMPLETED.equals(state) || FAILED.equals(state)
            || CANCELLED.equals(state) || EXPIRED.equals(state);
    }

    public static boolean transitionAllowed(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Commit the new session, run and initial event together. A concurrent loser
     * rolls its transaction back before the caller reads the original receipt.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OpenAiSessionContext accept(String runId, String model, String fingerprint,
                                       Supplier<OpenAiSessionContext> resolveSession) {
        OpenAiSessionContext context = resolveSession.get();
        Session session = context.session();
        bindProfile(session.profile());
        var repository = states();
        String revision = ProfileExecutionConfiguration.currentRevision(session.profile());
        if (repository.insertAcceptance(runId, session.id(), session.userId(), session.profile(), model,
                fingerprint, context.responseSessionId(), revision) == 0) {
            throw new AlreadyAccepted();
        }
        OpenAiRunStateEntity state = repository.findById(runId).orElseThrow();
        var created = new java.util.LinkedHashMap<String, Object>();
        created.put("event", "run.created");
        created.put("run_id", runId);
        created.put("timestamp", state.getCreatedAt().toEpochMilli() / 1000.0);
        if (revision != null) {
            created.put("profile_revision", revision);
        }
        appendTo(state, created);
        return context;
    }

    static final class AlreadyAccepted extends RuntimeException {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long recordEvent(String runId, String profile, String nextState, Map<String, Object> event) {
        bindProfile(profile);
        OpenAiRunStateEntity row = states().lockRun(runId, profile)
            .orElseThrow(() -> new IllegalStateException("Run state is unavailable: " + runId));
        if (isTerminal(row.getState())) {
            throw new IllegalStateException("Run is already terminal: " + runId);
        }
        if (nextState != null && !nextState.equals(row.getState())) {
            if (!transitionAllowed(row.getState(), nextState)) {
                throw new IllegalStateException("Invalid run transition " + row.getState() + " -> " + nextState);
            }
            row.setState(nextState);
            if (event.get("error") instanceof String reason) row.setStateReason(reason);
            if (CANCELLED.equals(nextState)) {
                row.setCancelledAt(Instant.now());
                if (event.get("reason") instanceof String reason) row.setCancelReason(reason);
            }
        }
        return appendTo(row, event);
    }

    private long appendTo(OpenAiRunStateEntity state, Map<String, Object> event) {
        long seq = state.getLastSeq() + 1;
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("run event serialization failed", e);
        }
        OpenAiRunEventEntity entity = new OpenAiRunEventEntity();
        entity.setRunId(state.getRunId());
        entity.setSeq(seq);
        entity.setEventJson(json);
        entity.setCreatedAt(Instant.now());
        eventLog().save(entity);

        state.setLastSeq(seq);
        state.setUpdatedAt(Instant.now());
        states().save(state);
        return seq;
    }

    @Transactional(readOnly = true)
    public Optional<OpenAiRunStateEntity> state(String runId, String profile) {
        bindProfile(profile);
        return states().findByRunIdAndProfile(runId, profile);
    }

    public record ParsedEvent(long seq, Map<String, Object> payload) {}

    @Transactional(readOnly = true)
    public List<ParsedEvent> replayParsed(String runId, String profile, long afterSeq, int limit) {
        bindProfile(profile);
        return eventLog().replayAfter(runId, profile, afterSeq, PageRequest.of(0, Math.max(1, limit))).stream()
            .map(entity -> new ParsedEvent(entity.getSeq(), parse(entity.getEventJson())))
            .toList();
    }

    private void bindProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("Profile is required for native run persistence");
        }
        if (entityManager == null) {
            return;
        }
        entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
            if ("H2".equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
            if (connection.getAutoCommit()) {
                throw new SQLException("Native run persistence requires a transaction");
            }
            try (var bind = connection.prepareStatement("""
                SELECT set_config('pdlc.java.profile', ?, true)
                WHERE COALESCE(NULLIF(current_setting('pdlc.java.profile', true), ''), ?) = ?
                """)) {
                for (int parameter = 1; parameter <= 3; parameter++) {
                    bind.setString(parameter, profile);
                }
                try (var result = bind.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("A native run transaction cannot change its profile");
                    }
                }
            }
        });
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of("event", "unparseable", "raw", json);
        }
    }

    private OpenAiRunStateRepository states() {
        OpenAiRunStateRepository repository = stateRepositoryProvider == null
            ? null : stateRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("run state repository is unavailable");
        }
        return repository;
    }

    private OpenAiRunEventRepository eventLog() {
        OpenAiRunEventRepository repository = eventRepositoryProvider == null
            ? null : eventRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("run event repository is unavailable");
        }
        return repository;
    }
}
