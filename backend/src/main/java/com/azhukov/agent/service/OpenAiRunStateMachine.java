package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.OpenAiRunEventEntity;
import com.azhukov.agent.persistence.entity.OpenAiRunStateEntity;
import com.azhukov.agent.persistence.repository.OpenAiRunEventRepository;
import com.azhukov.agent.persistence.repository.OpenAiRunStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
        REQUIRES_ACTION, Set.of(IN_PROGRESS, CANCELLED, EXPIRED, FAILED),
        COMPLETED, Set.of(),
        FAILED, Set.of(),
        CANCELLED, Set.of(),
        EXPIRED, Set.of());

    private final ObjectProvider<OpenAiRunStateRepository> stateRepositoryProvider;
    private final ObjectProvider<OpenAiRunEventRepository> eventRepositoryProvider;
    private final ObjectMapper objectMapper;

    public static boolean isTerminal(String state) {
        return COMPLETED.equals(state) || FAILED.equals(state)
            || CANCELLED.equals(state) || EXPIRED.equals(state);
    }

    public static boolean transitionAllowed(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public record TransitionResult(boolean accepted, String from, String to, String reason) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createRun(String runId, UUID sessionId, String userId, String profile,
                          String model, String externalRunId) {
        OpenAiRunStateRepository repository = states();
        if (repository.existsById(runId)) {
            return; // idempotent create
        }
        OpenAiRunStateEntity entity = new OpenAiRunStateEntity();
        entity.setRunId(runId);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setProfile(profile == null || profile.isBlank() ? "default" : profile);
        entity.setModel(model);
        entity.setExternalRunId(externalRunId);
        entity.setState(QUEUED);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    /** Race-safe guarded transition; the loser gets accepted=false. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransitionResult transition(String runId, String to, String reason) {
        OpenAiRunStateRepository repository = states();
        Optional<OpenAiRunStateEntity> existing = repository.findById(runId);
        if (existing.isEmpty()) {
            return new TransitionResult(false, null, to, "run not found");
        }
        String from = existing.get().getState();
        if (from.equals(to)) {
            return new TransitionResult(true, from, to, "already in state"); // idempotent
        }
        if (isTerminal(from)) {
            return new TransitionResult(false, from, to, "run is terminal: " + from);
        }
        if (!transitionAllowed(from, to)) {
            return new TransitionResult(false, from, to, "invalid transition " + from + " -> " + to);
        }
        Instant now = Instant.now();
        Instant cancelledAt = CANCELLED.equals(to) ? now : null;
        String cancelReason = CANCELLED.equals(to) ? reason : null;
        int updated = repository.transition(runId, from, to, reason, cancelReason, cancelledAt,
            null, now);
        if (updated == 0) {
            // lost the race — re-read the actual state
            String actual = repository.findById(runId).map(OpenAiRunStateEntity::getState)
                .orElse(from);
            return new TransitionResult(false, actual, to, "concurrent transition");
        }
        return new TransitionResult(true, from, to, reason);
    }

    /** Append an event with a per-run monotonic sequence; returns the seq. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long appendEvent(String runId, Map<String, Object> event) {
        OpenAiRunStateRepository states = states();
        OpenAiRunEventRepository events = eventLog();
        OpenAiRunStateEntity state = states.findById(runId).orElse(null);
        if (state == null) {
            return -1;
        }
        long seq = state.getLastSeq() + 1;
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("run event serialization failed", e);
        }
        OpenAiRunEventEntity entity = new OpenAiRunEventEntity();
        entity.setRunId(runId);
        entity.setSeq(seq);
        entity.setEventJson(json);
        entity.setCreatedAt(Instant.now());
        events.save(entity);

        // persist the cursor atomically; retry on concurrent append is the
        // caller's serialization responsibility (single writer per run)
        state.setLastSeq(seq);
        state.setUpdatedAt(Instant.now());
        states.save(state);
        return seq;
    }

    @Transactional(readOnly = true)
    public Optional<OpenAiRunStateEntity> state(String runId) {
        return states().findById(runId);
    }

    @Transactional(readOnly = true)
    public List<OpenAiRunEventEntity> replay(String runId, long afterSeq, int limit) {
        return eventLog().replayAfter(runId, afterSeq, PageRequest.of(0, Math.max(1, limit)));
    }

    public record ParsedEvent(long seq, Map<String, Object> payload) {}

    public List<ParsedEvent> replayParsed(String runId, long afterSeq, int limit) {
        return replay(runId, afterSeq, limit).stream()
            .map(entity -> new ParsedEvent(entity.getSeq(), parse(entity.getEventJson())))
            .toList();
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
