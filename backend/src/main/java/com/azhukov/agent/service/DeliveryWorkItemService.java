package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity;
import com.azhukov.agent.persistence.repository.DeliveryWorkItemRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
/**
 * Owns durable final-delivery state; transports may only act through claimed work.
 */
@Service
public class DeliveryWorkItemService {

    public static final String SOURCE_CRON_EXECUTION = "cron_execution";
    public static final String SOURCE_DELEGATED_TASK_RUN = "delegated_task_run";
    public static final String TARGET_LOCAL = "local";
    public static final String TARGET_PLATFORM = "platform";
    public static final String STATE_PENDING = "pending";
    public static final String STATE_CLAIMED = "claimed";
    public static final String STATE_DELIVERED = "delivered";
    public static final String STATE_DROPPED = "dropped";
    public static final String STATE_UNKNOWN = "unknown";
    public static final String STATE_LOCAL_DELIVERED = "local_delivered";

    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_PAYLOAD_CHARS = 2_000_000;
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(5);

    private final DeliveryWorkItemRepository repository;
    private final org.springframework.beans.factory.ObjectProvider<DelegatedCompletionClassifier> classifierProvider;

    /** Legacy constructor without the classifier (tests of non-delegate delivery). */
    public DeliveryWorkItemService(DeliveryWorkItemRepository repository) {
        this.repository = repository;
        this.classifierProvider = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryWorkItemService(
            DeliveryWorkItemRepository repository,
            org.springframework.beans.factory.ObjectProvider<DelegatedCompletionClassifier> classifierProvider) {
        this.repository = repository;
        this.classifierProvider = classifierProvider;
    }

    @Transactional
    public DeliveryWorkItemEntity enqueue(EnqueueRequest request) {
        ValidatedRequest validated = validate(request);
        return repository.findBySourceTypeAndSourceIdAndTargetHash(
                validated.item().getSourceType(),
                validated.item().getSourceId(),
                validated.item().getTargetHash())
            .orElseGet(() -> repository.save(validated.item()));
    }

    @Transactional
    public Optional<DeliveryWorkItemEntity> find(
            String sourceType, String sourceId, String target) {
        Target parsed = parseTarget(target);
        return repository.findBySourceTypeAndSourceIdAndTargetHash(sourceType, sourceId, hash(parsed.canonical()));
    }

    @Transactional
    public Optional<ClaimedWorkItem> claimNext(String consumerId, List<String> profiles) {
        List<String> normalizedProfiles = normalizeProfiles(profiles);
        if (normalizedProfiles.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<DeliveryWorkItemEntity> candidates = repository.findClaimable(
            normalizedProfiles, now, PageRequest.of(0, 25));
        for (DeliveryWorkItemEntity candidate : candidates) {
            String claimToken = claimToken(consumerId);
            if (repository.claimPending(candidate.getId(), claimToken, now) != 1) {
                continue;
            }
            // Hermes _completion_delivery_ready parity (WP-1 tail): the durable
            // claim is spent, but a delivery attempt is NOT. Classify delegated
            // run targets right after the atomic claim and settle unusable ones
            // immediately: TERMINAL -> drop (user-closed parent, never falsely
            // retry), RETRY -> release with delay (rotation mid-flight / DB
            // hiccup). Only DELIVER reaches the consumer.
            if (SOURCE_DELEGATED_TASK_RUN.equals(candidate.getSourceType())
                && classifierProvider != null) {
                DelegatedCompletionClassifier classifier = classifierProvider.getIfAvailable();
                if (classifier != null) {
                    DelegatedCompletionClassifier.Verdict verdict =
                        classifier.classify(candidate.getParentSessionId());
                    if (verdict == DelegatedCompletionClassifier.Verdict.TERMINAL) {
                        repository.markTerminal(candidate.getId(), claimToken, STATE_DROPPED,
                            Instant.now(), "completion_target_terminal", null);
                        continue;
                    }
                    if (verdict == DelegatedCompletionClassifier.Verdict.RETRY) {
                        repository.releaseKnownFailure(candidate.getId(), claimToken,
                            now.plus(RETRY_BASE_DELAY), "completion_target_retry", null);
                        continue;
                    }
                }
            }
            return repository.findById(candidate.getId())
                .map(item -> new ClaimedWorkItem(item, claimToken));
        }
        return Optional.empty();
    }

    @Transactional
    public boolean markDelivered(UUID id, String claimToken, DeliveryReceipt receipt) {
        if (id == null || isBlank(claimToken) || receipt == null) {
            return false;
        }
        return repository.markDelivered(
            id,
            claimToken,
            Instant.now(),
            trim(receipt.outboundMessageId(), 255),
            trim(receipt.idempotencyKey(), 160)) == 1;
    }

    @Transactional
    public boolean markLocalDelivered(UUID id) {
        if (id == null) {
            return false;
        }
        DeliveryWorkItemEntity item = repository.findById(id).orElse(null);
        if (item == null || !STATE_PENDING.equals(item.getState()) || !TARGET_LOCAL.equals(item.getTargetKind())) {
            return false;
        }
        item.setState(STATE_LOCAL_DELIVERED);
        item.setDeliveredAt(Instant.now());
        return true;
    }

    @Transactional
    public boolean releaseKnownFailure(UUID id, String claimToken, String category, String detail) {
        if (id == null || isBlank(claimToken)) {
            return false;
        }
        DeliveryWorkItemEntity item = repository.findById(id).orElse(null);
        if (item == null || !STATE_CLAIMED.equals(item.getState()) || !claimToken.equals(item.getClaimToken())) {
            return false;
        }
        if (item.getAttempts() >= MAX_ATTEMPTS) {
            return markTerminal(id, claimToken, STATE_DROPPED, category, detail);
        }
        return repository.releaseKnownFailure(
            id,
            claimToken,
            Instant.now().plus(retryDelay(item.getAttempts())),
            normalizeCategory(category),
            redactDetail(detail)) == 1;
    }

    @Transactional
    public boolean markUnknown(UUID id, String claimToken, String category, String detail) {
        return markTerminal(id, claimToken, STATE_UNKNOWN, category, detail);
    }

    @Transactional
    public boolean drop(UUID id, String claimToken, String category, String detail) {
        return markTerminal(id, claimToken, STATE_DROPPED, category, detail);
    }

    /**
     * WP-1 (Hermes sweep_recoverable): recovery for claims whose consumer died
     * between claim and ack/release. A claim older than the lease cutoff is
     * either returned to pending (bounded by the attempts cap) or terminalized
     * as dropped when the cap is exhausted. Called from a scheduled sweep on
     * the backend — the safety net for bot crashes mid-delivery.
     *
     * @return recovered = rows returned to pending, abandoned = rows over the cap
     */
    @Transactional
    public SweepResult sweepStaleClaims(Instant now, Duration leaseCutoff) {
        Instant cutoff = now.minus(leaseCutoff);
        List<DeliveryWorkItemEntity> stale = repository.findByStateAndClaimedAtBefore(STATE_CLAIMED, cutoff);
        int recovered = 0;
        int abandoned = 0;
        for (DeliveryWorkItemEntity item : stale) {
            if (item.getAttempts() >= MAX_ATTEMPTS) {
                abandoned += repository.markTerminal(
                    item.getId(),
                    item.getClaimToken(),
                    STATE_DROPPED,
                    Instant.now(),
                    "lease_expired_attempts_exhausted",
                    null) == 1 ? 1 : 0;
            } else {
                recovered += repository.releaseKnownFailure(
                    item.getId(),
                    item.getClaimToken(),
                    now.plus(RETRY_BASE_DELAY),
                    "lease_expired",
                    null) == 1 ? 1 : 0;
            }
        }
        return new SweepResult(recovered, abandoned);
    }

    public record SweepResult(int recovered, int abandoned) {}

    private boolean markTerminal(UUID id, String claimToken, String state, String category, String detail) {
        if (id == null || isBlank(claimToken)) {
            return false;
        }
        return repository.markTerminal(
            id,
            claimToken,
            state,
            Instant.now(),
            normalizeCategory(category),
            redactDetail(detail)) == 1;
    }

    private static ValidatedRequest validate(EnqueueRequest request) {
        if (request == null || isBlank(request.sourceType()) || isBlank(request.sourceId())) {
            throw new IllegalArgumentException("source_type and source_id are required");
        }
        if (!SOURCE_CRON_EXECUTION.equals(request.sourceType())
            && !SOURCE_DELEGATED_TASK_RUN.equals(request.sourceType())) {
            throw new IllegalArgumentException("unsupported delivery source_type: " + request.sourceType());
        }
        String payload = request.payloadText() == null ? "" : request.payloadText();
        if (payload.isBlank()) {
            throw new IllegalArgumentException("delivery payload must not be blank");
        }
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new IllegalArgumentException("delivery payload exceeds " + MAX_PAYLOAD_CHARS + " characters");
        }
        Target target = parseTarget(request.target());
        DeliveryWorkItemEntity item = new DeliveryWorkItemEntity();
        item.setId(UUID.randomUUID());
        item.setSourceType(request.sourceType().trim());
        item.setSourceId(request.sourceId().trim());
        item.setProfile(normalizeProfile(request.profile()));
        item.setUserId(trim(request.userId(), 255));
        item.setParentSessionId(request.parentSessionId());
        item.setTargetKind(target.kind());
        item.setPlatform(target.platform());
        item.setChatId(target.chatId());
        item.setThreadId(target.threadId());
        item.setTargetHash(hash(target.canonical()));
        item.setPayloadText(payload);
        item.setPayloadHash(hash(payload));
        item.setState(STATE_PENDING);
        item.setAttempts(0);
        item.setAvailableAt(Instant.now());
        item.setCreatedAt(Instant.now());
        return new ValidatedRequest(item);
    }

    static Target parseTarget(String target) {
        String value = target == null ? "" : target.trim();
        if ("local".equalsIgnoreCase(value)) {
            return new Target(TARGET_LOCAL, null, null, null, "local");
        }
        String[] parts = value.split(":", -1);
        if ((parts.length == 2 || parts.length == 3)
            && !isBlank(parts[0]) && !isBlank(parts[1])) {
            String platform = parts[0].trim().toLowerCase(Locale.ROOT);
            String chatId = parts[1].trim();
            String threadId = parts.length == 3 ? trim(parts[2], 255) : null;
            return new Target(
                TARGET_PLATFORM,
                platform,
                chatId,
                threadId,
                threadId == null ? platform + ":" + chatId : platform + ":" + chatId + ":" + threadId);
        }
        throw new IllegalArgumentException(
            "delivery target must be local, platform:chat_id, or platform:chat_id:thread_id; origin must be resolved before enqueue");
    }

    private static List<String> normalizeProfiles(List<String> profiles) {
        if (profiles == null) {
            return List.of();
        }
        return profiles.stream()
            .map(DeliveryWorkItemService::normalizeProfile)
            .distinct()
            .toList();
    }

    private static String normalizeProfile(String profile) {
        return isBlank(profile) ? "default" : profile.trim().toLowerCase(Locale.ROOT);
    }

    private static String claimToken(String consumerId) {
        String normalized = isBlank(consumerId) ? "consumer" : consumerId.trim().replaceAll("[^A-Za-z0-9_.:-]", "_");
        return trim(normalized, 120) + ":" + UUID.randomUUID();
    }

    private static Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 8);
        return RETRY_BASE_DELAY.multipliedBy(multiplier);
    }

    private static String normalizeCategory(String category) {
        return isBlank(category) ? "transport_error" : trim(category.trim().toLowerCase(Locale.ROOT), 64);
    }

    private static String redactDetail(String detail) {
        return trim(detail == null ? null : detail.replaceAll("(?i)(token|api[_-]?key|password)=\\S+", "$1=[redacted]"), 4_000);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record EnqueueRequest(
        String sourceType,
        String sourceId,
        String profile,
        String userId,
        UUID parentSessionId,
        String target,
        String payloadText
    ) {}

    public record DeliveryReceipt(String outboundMessageId, String idempotencyKey) {}

    public record ClaimedWorkItem(DeliveryWorkItemEntity item, String claimToken) {}

    static record Target(String kind, String platform, String chatId, String threadId, String canonical) {}

    private record ValidatedRequest(DeliveryWorkItemEntity item) {}
}
