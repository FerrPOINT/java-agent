package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-approval gate: if enabled, memory writes are staged to a pending queue
 * instead of being applied directly. Users can approve or reject pending writes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteApprovalGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PendingMemoryRepository pendingRepository;
    private final MemoryProvider memoryProvider;
    private final AgentProperties properties;
    private volatile boolean enabled;



    @PostConstruct
    void init() {
        enabled = properties.getMemory().isWriteApproval();
    }
    public boolean isEnabled() {
        return enabled;
    }

    public void setApproval(boolean enabled) {
        this.enabled = enabled;
        log.info("Write-approval gate {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Stage a write to the pending queue.
     * @return the pending entry ID, or null on error
     */
    public UUID stageWrite(String userId, String action, String target, String content,
                           String oldText, String summary, String origin) {
        try {
            PendingMemoryEntity e = new PendingMemoryEntity();
            e.setUserId(userId);
            e.setAction(action);
            e.setTarget(target != null ? target : "memory");
            e.setContent(content);
            e.setOldText(oldText);
            e.setSummary(summary);
            e.setOrigin(origin != null ? origin : "foreground");
            e.setStatus("pending");
            e.setCreatedAt(Instant.now());
            PendingMemoryEntity saved = pendingRepository.save(e);
            log.debug("Staged memory write for user {}: action={}, target={}, id={}",
                userId, action, target, saved.getId());
            return saved.getId();
        } catch (Exception ex) {
            log.error("Failed to stage memory write: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * List all pending writes for a user.
     */
    public List<PendingMemoryEntity> listPending(String userId) {
        return pendingRepository.findByUserIdAndStatus(userId, "pending");
    }

    /**
     * Approve a pending write — apply it to memory.
     * @return true on success, false if not found or already resolved
     */
    @Transactional
    public boolean approve(String userId, UUID id) {
        Optional<PendingMemoryEntity> opt = pendingRepository.findByIdAndUserId(id, userId);
        if (opt.isEmpty()) return false;
        PendingMemoryEntity e = opt.get();
        if (!"pending".equals(e.getStatus())) return false;

        String action = e.getAction();
        String target = e.getTarget();
        try {
            switch (action) {
                case "add" -> memoryProvider.store(userId, target, "auto", e.getContent());
                case "replace" -> {
                    String error = memoryProvider.replace(userId, target, e.getOldText(), e.getContent());
                    if (error != null) {
                        throw new IllegalStateException(error);
                    }
                }
                case "remove" -> {
                    String error = memoryProvider.remove(userId, target, e.getOldText());
                    if (error != null) {
                        throw new IllegalStateException(error);
                    }
                }
                case "batch" -> {
                    String error = memoryProvider.applyBatch(
                        userId, target, parseBatchOperations(e.getContent()), approvalProvenance(e, id));
                    if (error != null) {
                        throw new IllegalStateException(error);
                    }
                }
                default -> {
                    log.warn("Unknown pending action: {}", action);
                    return false;
                }
            }
            e.setStatus("approved");
            e.setResolvedAt(Instant.now());
            pendingRepository.save(e);
            log.debug("Approved memory write {}: action={}, target={}", id, action, target);
            return true;
        } catch (Exception ex) {
            // Audit H6: do not swallow exceptions inside @Transactional — mark
            // the transaction for rollback so the pending status is NOT saved
            // as "approved" while the memory write failed (breaks atomicity).
            log.error("Failed to apply approved write {}: {}", id, ex.getMessage());
            markRollbackOnlyIfPossible();
            return false;
        }
    }

    private static List<MemoryProvider.MemoryBatchOperation> parseBatchOperations(String content)
        throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(content == null ? "" : content);
        JsonNode operations = root.isObject() ? root.path("operations") : root;
        if (!operations.isArray()) {
            throw new IllegalArgumentException("Pending batch content must be a JSON operations array");
        }

        List<MemoryProvider.MemoryBatchOperation> parsed = new ArrayList<>(operations.size());
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                throw new IllegalArgumentException("Pending batch operation must be an object");
            }
            String contentText = textValue(operation, "content");
            if (contentText == null || contentText.isBlank()) {
                contentText = textValue(operation, "new_text");
                if (contentText == null) {
                    contentText = textValue(operation, "newText");
                }
            }
            String oldText = textValue(operation, "old_text");
            if (oldText == null) {
                oldText = textValue(operation, "oldText");
            }
            parsed.add(new MemoryProvider.MemoryBatchOperation(
                textValue(operation, "action"),
                contentText,
                oldText));
        }
        return parsed;
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private static Map<String, String> approvalProvenance(PendingMemoryEntity entity, UUID id) {
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put("approved_pending_id", id.toString());
        if (entity.getOrigin() != null && !entity.getOrigin().isBlank()) {
            provenance.put("origin", entity.getOrigin());
        }
        return Map.copyOf(provenance);
    }

    private static void markRollbackOnlyIfPossible() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // Unit tests may call approve() without a Spring transactional proxy.
        }
    }

    /**
     * Reject a pending write.
     * @return true on success, false if not found or already resolved
     */
    public boolean reject(String userId, UUID id) {
        Optional<PendingMemoryEntity> opt = pendingRepository.findByIdAndUserId(id, userId);
        if (opt.isEmpty()) return false;
        PendingMemoryEntity e = opt.get();
        if (!"pending".equals(e.getStatus())) return false;
        e.setStatus("rejected");
        e.setResolvedAt(Instant.now());
        pendingRepository.save(e);
        log.debug("Rejected memory write {}: action={}, target={}", id, e.getAction(), e.getTarget());
        return true;
    }
}
