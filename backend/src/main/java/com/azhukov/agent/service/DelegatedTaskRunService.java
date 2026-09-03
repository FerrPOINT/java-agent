package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.repository.DelegatedTaskRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DelegatedTaskRunService {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_TIMEOUT = "timeout";
    public static final String STATUS_INTERRUPTED = "interrupted";
    public static final String STATUS_CANCEL_REQUESTED = "cancel_requested";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final Set<String> LIVE_STATUSES = Set.of(STATUS_RUNNING, STATUS_CANCEL_REQUESTED);
    private static final Set<String> TERMINAL_STATUSES = Set.of(
        STATUS_COMPLETED, STATUS_FAILED, STATUS_ERROR, STATUS_TIMEOUT, STATUS_INTERRUPTED, STATUS_CANCELLED
    );
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_DELIVERY_ATTEMPTS = 8;
    private static final Duration DELIVERY_CLAIM_STALE_AFTER = Duration.ofMinutes(5);
    private static final Duration MAX_COMPLETION_REPLAY_AGE = Duration.ofHours(48);

    private final DelegatedTaskRunRepository repository;
    private final ObjectMapper objectMapper;
    private final EventService eventService;
    private final Object capacityLock = new Object();

    public DelegatedTaskRunService(DelegatedTaskRunRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    @Autowired
    public DelegatedTaskRunService(
        DelegatedTaskRunRepository repository,
        ObjectMapper objectMapper,
        EventService eventService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
    }

    @Transactional
    public DelegatedTaskRunEntity create(UUID parentSessionId, String profile, String goal) {
        return createRun(parentSessionId, profile, goal);
    }

    @Transactional
    public CreateAttempt createIfCapacity(UUID parentSessionId, String profile, String goal, int maxActiveRuns) {
        if (parentSessionId == null) {
            throw new IllegalArgumentException("parent_session_id is required");
        }
        int capacity = Math.max(1, maxActiveRuns);
        synchronized (capacityLock) {
            long active = activeCountForParent(parentSessionId);
            if (active >= capacity) {
                return new CreateAttempt(false, null, active, capacity);
            }
            return new CreateAttempt(true, createRun(parentSessionId, profile, goal), active, capacity);
        }
    }

    private DelegatedTaskRunEntity createRun(UUID parentSessionId, String profile, String goal) {
        if (parentSessionId == null) {
            throw new IllegalArgumentException("parent_session_id is required");
        }
        String normalizedGoal = goal == null || goal.isBlank() ? "Delegated task" : goal.trim();
        Instant now = Instant.now();
        DelegatedTaskRunEntity entity = new DelegatedTaskRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setParentSessionId(parentSessionId);
        entity.setProfile(normalizeProfile(profile));
        entity.setGoal(normalizedGoal);
        entity.setStatus(STATUS_RUNNING);
        entity.setCreatedAt(now);
        DelegatedTaskRunEntity saved = repository.save(entity);
        publish("delegate.created", saved, null);
        return saved;
    }

    @Transactional
    public DelegatedTaskRunEntity markStarted(UUID runId, UUID childSessionId) {
        DelegatedTaskRunEntity entity = require(runId);
        boolean changed = false;
        if (!isTerminal(entity.getStatus())) {
            if (entity.getChildSessionId() == null) {
                entity.setChildSessionId(childSessionId);
                changed = true;
            }
            if (entity.getStartedAt() == null) {
                entity.setStartedAt(Instant.now());
                changed = true;
            }
            if (entity.getStatus() == null || entity.getStatus().isBlank()) {
                entity.setStatus(STATUS_RUNNING);
                changed = true;
            }
        }
        DelegatedTaskRunEntity saved = repository.save(entity);
        if (changed) {
            publish("delegate.started", saved, null);
        }
        return saved;
    }

    @Transactional
    public DelegatedTaskRunEntity finish(UUID runId, String status, Object result, String error) {
        DelegatedTaskRunEntity entity = require(runId);
        String normalizedStatus = normalizeTerminalStatus(status);
        String effectiveError = error;
        if (STATUS_CANCEL_REQUESTED.equals(entity.getStatus())
            && STATUS_COMPLETED.equals(normalizedStatus)) {
            normalizedStatus = STATUS_INTERRUPTED;
            effectiveError = effectiveError == null || effectiveError.isBlank() ? "cancelled" : effectiveError;
        }
        entity.setStatus(normalizedStatus);
        entity.setResultJson(serializeResult(result));
        entity.setError(blankToNull(effectiveError));
        entity.setCompletedAt(Instant.now());
        DelegatedTaskRunEntity saved = repository.save(entity);
        publish("delegate." + normalizedStatus, saved, completionPayload(saved, false));
        return saved;
    }

    @Transactional
    public DelegatedTaskRunEntity fail(UUID runId, String error) {
        return finish(runId, STATUS_ERROR, Map.of("error", error == null ? "Delegation failed" : error), error);
    }

    @Transactional
    public DelegatedTaskRunEntity requestCancel(UUID runId, UUID parentSessionId) {
        DelegatedTaskRunEntity entity = requireOwned(runId, parentSessionId);
        if (!isTerminal(entity.getStatus())) {
            entity.setStatus(STATUS_CANCEL_REQUESTED);
            entity.setCancelRequestedAt(Instant.now());
            DelegatedTaskRunEntity saved = repository.save(entity);
            publish("delegate.cancel_requested", saved, null);
            return saved;
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public boolean isCancelRequested(UUID runId) {
        return repository.findById(runId)
            .map(entity -> STATUS_CANCEL_REQUESTED.equals(entity.getStatus()))
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<DelegatedTaskRunEntity> findForParent(UUID runId, UUID parentSessionId) {
        if (runId == null || parentSessionId == null) {
            return Optional.empty();
        }
        return repository.findById(runId)
            .filter(entity -> parentSessionId.equals(entity.getParentSessionId()));
    }

    @Transactional(readOnly = true)
    public List<DelegatedTaskRunEntity> listForParent(UUID parentSessionId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 100));
        return repository.findByParentSessionIdOrderByCreatedAtDesc(parentSessionId, PageRequest.of(0, boundedLimit));
    }

    @Transactional(readOnly = true)
    public List<DelegatedTaskRunEntity> pendingDelivery(UUID parentSessionId, String profile, int limit) {
        if (parentSessionId == null) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 100));
        if (profile == null || profile.isBlank() || "all".equalsIgnoreCase(profile.trim())) {
            return repository.findByParentSessionIdAndCompletedAtIsNotNullAndDeliveredAtIsNullOrderByCompletedAtAsc(
                parentSessionId,
                PageRequest.of(0, boundedLimit));
        }
        return repository.findPendingDeliveryForProfile(
            parentSessionId,
            normalizeProfile(profile),
            PageRequest.of(0, boundedLimit));
    }

    @Transactional
    public int restorePendingCompletions(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 500));
        Instant now = Instant.now();
        List<DelegatedTaskRunEntity> pending =
            repository.findRestorablePendingDelivery(
                now.minus(DELIVERY_CLAIM_STALE_AFTER),
                PageRequest.of(0, boundedLimit));
        Instant replayCutoff = now.minus(MAX_COMPLETION_REPLAY_AGE);
        int restored = 0;
        for (DelegatedTaskRunEntity entity : pending) {
            Instant ageBasis = entity.getCompletedAt() != null ? entity.getCompletedAt() : entity.getCreatedAt();
            if (ageBasis != null && ageBasis.isBefore(replayCutoff)) {
                dropExpiredPendingCompletion(entity, now);
                continue;
            }
            publish("delegate." + normalizeTerminalStatus(entity.getStatus()), entity, completionPayload(entity, true));
            restored++;
        }
        return restored;
    }

    @EventListener(ApplicationReadyEvent.class)
    void restorePendingCompletionsOnStartup() {
        if (eventService == null) {
            return;
        }
        try {
            int restored = restorePendingCompletions(500);
            if (restored > 0) {
                log.info("Restored {} pending delegated task completion event(s)", restored);
            }
        } catch (RuntimeException e) {
            log.warn("Could not restore pending delegated task completions: {}", e.getMessage());
        }
    }

    @Transactional
    public Optional<DeliveryClaim> claimCompletionDelivery(UUID runId, String consumer) {
        if (runId == null) {
            return Optional.empty();
        }
        String claimId = deliveryClaimId(consumer);
        Instant now = Instant.now();
        int claimed = repository.claimPendingDelivery(
            runId,
            claimId,
            now,
            now.minus(DELIVERY_CLAIM_STALE_AFTER));
        if (claimed != 1) {
            return Optional.empty();
        }
        return repository.findById(runId)
            .map(entity -> {
                publish("delegate.delivery_claimed", entity, Map.of(
                    "delivery_claim", claimId,
                    "delivery_state", deliveryState(entity)));
                return new DeliveryClaim(entity.getId(), claimId, entity);
            });
    }

    @Transactional
    public boolean completeDeliveryClaim(UUID runId, String claimId, String target, String idempotencyKey) {
        String normalizedClaim = blankToNull(claimId);
        if (runId == null || normalizedClaim == null) {
            return false;
        }
        int completed = repository.completeDeliveryClaim(
            runId,
            normalizedClaim,
            blankToNull(target),
            blankToNull(idempotencyKey),
            Instant.now());
        if (completed != 1) {
            return false;
        }
        repository.findById(runId)
            .ifPresent(entity -> publish("delegate.delivered", entity, Map.of(
                "delivery_state", deliveryState(entity),
                "delivery_pending", false)));
        return true;
    }

    @Transactional
    public boolean releaseDeliveryClaim(UUID runId, String claimId, String target, String error) {
        Optional<DelegatedTaskRunEntity> maybeEntity = findClaimedPendingRun(runId, claimId);
        if (maybeEntity.isEmpty()) {
            return false;
        }
        DelegatedTaskRunEntity entity = maybeEntity.get();
        entity.setDeliveryTarget(blankToNull(target));
        entity.setDeliveryError(blankToNull(error));
        entity.setDeliveryClaim(null);
        entity.setDeliveryClaimedAt(null);
        if (entity.getDeliveryAttempts() >= MAX_DELIVERY_ATTEMPTS) {
            entity.setDeliveryDroppedAt(Instant.now());
            DelegatedTaskRunEntity saved = repository.save(entity);
            publish("delegate.delivery_dropped", saved, Map.of(
                "delivery_state", deliveryState(saved),
                "delivery_pending", false));
        } else {
            DelegatedTaskRunEntity saved = repository.save(entity);
            publish("delegate.delivery_released", saved, Map.of(
                "delivery_state", deliveryState(saved),
                "delivery_pending", true));
        }
        return true;
    }

    @Transactional
    public boolean dropDeliveryClaim(UUID runId, String claimId, String target, String error) {
        Optional<DelegatedTaskRunEntity> maybeEntity = findClaimedPendingRun(runId, claimId);
        if (maybeEntity.isEmpty()) {
            return false;
        }
        DelegatedTaskRunEntity entity = maybeEntity.get();
        entity.setDeliveryDroppedAt(Instant.now());
        entity.setDeliveryTarget(blankToNull(target));
        entity.setDeliveryError(blankToNull(error));
        entity.setDeliveryClaim(null);
        entity.setDeliveryClaimedAt(null);
        DelegatedTaskRunEntity saved = repository.save(entity);
        publish("delegate.delivery_dropped", saved, Map.of(
            "delivery_state", deliveryState(saved),
            "delivery_pending", false));
        return true;
    }

    @Transactional
    public DelegatedTaskRunEntity markDelivered(UUID runId, String target, String idempotencyKey) {
        DelegatedTaskRunEntity entity = require(runId);
        requireCompletedForDelivery(entity);
        entity.setDeliveredAt(Instant.now());
        entity.setDeliveryDroppedAt(null);
        entity.setDeliveryTarget(blankToNull(target));
        entity.setDeliveryIdempotencyKey(blankToNull(idempotencyKey));
        entity.setDeliveryError(null);
        entity.setDeliveryClaim(null);
        entity.setDeliveryClaimedAt(null);
        DelegatedTaskRunEntity saved = repository.save(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery_target", saved.getDeliveryTarget());
        payload.put("delivery_idempotency_key", saved.getDeliveryIdempotencyKey());
        payload.put("delivery_pending", false);
        payload.put("delivery_state", deliveryState(saved));
        publish("delegate.delivered", saved, payload);
        return saved;
    }

    @Transactional
    public DelegatedTaskRunEntity markDeliveryFailed(UUID runId, String target, String error) {
        DelegatedTaskRunEntity entity = require(runId);
        requireCompletedForDelivery(entity);
        entity.setDeliveryTarget(blankToNull(target));
        entity.setDeliveryError(blankToNull(error));
        entity.setDeliveryAttempts(entity.getDeliveryAttempts() + 1);
        if (entity.getDeliveryAttempts() >= MAX_DELIVERY_ATTEMPTS) {
            entity.setDeliveryDroppedAt(Instant.now());
        }
        DelegatedTaskRunEntity saved = repository.save(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery_target", saved.getDeliveryTarget());
        payload.put("delivery_error", saved.getDeliveryError());
        payload.put("delivery_attempts", saved.getDeliveryAttempts());
        payload.put("delivery_pending", saved.getDeliveryDroppedAt() == null);
        payload.put("delivery_state", deliveryState(saved));
        publish(saved.getDeliveryDroppedAt() == null ? "delegate.delivery_failed" : "delegate.delivery_dropped", saved, payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public long activeCountForParent(UUID parentSessionId) {
        if (parentSessionId == null) {
            return 0;
        }
        return repository.countByParentSessionIdAndStatusIn(parentSessionId, LIVE_STATUSES);
    }

    private DelegatedTaskRunEntity require(UUID runId) {
        return repository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Delegated run '" + runId + "' was not found."));
    }

    private Optional<DelegatedTaskRunEntity> findClaimedPendingRun(UUID runId, String claimId) {
        String normalizedClaim = blankToNull(claimId);
        if (runId == null || normalizedClaim == null) {
            return Optional.empty();
        }
        return repository.findById(runId)
            .filter(entity -> entity.getCompletedAt() != null)
            .filter(entity -> entity.getDeliveredAt() == null)
            .filter(entity -> entity.getDeliveryDroppedAt() == null)
            .filter(entity -> normalizedClaim.equals(entity.getDeliveryClaim()));
    }

    private void dropExpiredPendingCompletion(DelegatedTaskRunEntity entity, Instant now) {
        entity.setDeliveryDroppedAt(now);
        entity.setDeliveryClaim(null);
        entity.setDeliveryClaimedAt(null);
        entity.setDeliveryTarget("restore");
        entity.setDeliveryError("Pending completion is older than 48 hours; dropped instead of replaying.");
        DelegatedTaskRunEntity saved = repository.save(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery_state", deliveryState(saved));
        payload.put("delivery_pending", false);
        payload.put("replay_age_cap_hours", MAX_COMPLETION_REPLAY_AGE.toHours());
        payload.put("restored", false);
        publish("delegate.delivery_dropped", saved, payload);
    }

    private DelegatedTaskRunEntity requireOwned(UUID runId, UUID parentSessionId) {
        return findForParent(runId, parentSessionId)
            .orElseThrow(() -> new IllegalArgumentException("Delegated run '" + runId + "' was not found for this session."));
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    private String normalizeTerminalStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_ERROR;
        }
        String normalized = status.trim().toLowerCase();
        if (TERMINAL_STATUSES.contains(normalized)) {
            return normalized;
        }
        return switch (normalized) {
            case STATUS_RUNNING, STATUS_CANCEL_REQUESTED -> normalized;
            default -> STATUS_ERROR;
        };
    }

    private String serializeResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof String value) {
            return value;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"Failed to serialize delegated task result\"}";
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeProfile(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"default".equals(normalized) && !PROFILE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid profile name: " + value);
        }
        return normalized;
    }

    private void requireCompletedForDelivery(DelegatedTaskRunEntity entity) {
        if (entity.getCompletedAt() == null) {
            throw new IllegalStateException("Delegated run '" + entity.getId() + "' has not completed.");
        }
    }

    private void publish(String eventType, DelegatedTaskRunEntity entity, Map<String, Object> extraPayload) {
        if (eventService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", entity.getId());
        payload.put("delegation_id", delegationId(entity.getId()));
        payload.put("parent_session_id", entity.getParentSessionId());
        payload.put("child_session_id", entity.getChildSessionId());
        payload.put("goal", entity.getGoal());
        payload.put("status", entity.getStatus());
        payload.put("error", entity.getError());
        payload.put("completed_at", entity.getCompletedAt());
        payload.put("delivered_at", entity.getDeliveredAt());
        payload.put("delivery_dropped_at", entity.getDeliveryDroppedAt());
        payload.put("delivery_target", entity.getDeliveryTarget());
        payload.put("delivery_error", entity.getDeliveryError());
        payload.put("delivery_attempts", entity.getDeliveryAttempts());
        payload.put("delivery_claimed_at", entity.getDeliveryClaimedAt());
        payload.put("delivery_state", deliveryState(entity));
        if (extraPayload != null) {
            payload.putAll(extraPayload);
        }
        try {
            eventService.publish(eventType, entity.getProfile(), entity.getParentSessionId(), entity.getId(), payload);
        } catch (RuntimeException e) {
            log.warn("Failed to publish delegated task event {} for {}: {}", eventType, entity.getId(), e.getMessage());
        }
    }

    private Map<String, Object> completionPayload(DelegatedTaskRunEntity entity, boolean restored) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delivery_pending", entity.getCompletedAt() != null
            && entity.getDeliveredAt() == null
            && entity.getDeliveryDroppedAt() == null);
        payload.put("delivery_state", deliveryState(entity));
        if (restored) {
            payload.put("restored", true);
        }
        if (entity.getResultJson() != null && !entity.getResultJson().isBlank()) {
            payload.put("result_json", entity.getResultJson());
            payload.put("result", parseResultJson(entity.getResultJson()));
        }
        return payload;
    }

    private Object parseResultJson(String resultJson) {
        try {
            return objectMapper.readValue(resultJson, Object.class);
        } catch (Exception e) {
            return resultJson;
        }
    }

    private String delegationId(UUID runId) {
        return "deleg_" + runId;
    }

    private String deliveryClaimId(String consumer) {
        String normalizedConsumer = consumer == null || consumer.isBlank()
            ? "consumer"
            : consumer.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
        if (normalizedConsumer.length() > 80) {
            normalizedConsumer = normalizedConsumer.substring(0, 80);
        }
        return normalizedConsumer + ":" + UUID.randomUUID();
    }

    private String deliveryState(DelegatedTaskRunEntity entity) {
        if (entity.getDeliveredAt() != null) {
            return "delivered";
        }
        if (entity.getDeliveryDroppedAt() != null) {
            return "dropped";
        }
        if (entity.getCompletedAt() == null) {
            return "not_ready";
        }
        if (entity.getDeliveryClaim() != null && !entity.getDeliveryClaim().isBlank()) {
            return "claimed";
        }
        return "pending";
    }

    public record CreateAttempt(
        boolean accepted,
        DelegatedTaskRunEntity run,
        long activeCount,
        int capacity
    ) {
    }

    public record DeliveryClaim(
        UUID runId,
        String claimId,
        DelegatedTaskRunEntity run
    ) {
    }
}
