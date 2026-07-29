package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-approval gate: if enabled, memory writes are staged to a pending queue
 * instead of being applied directly. Users can approve or reject pending writes.
 */
@Slf4j
@Component
public class WriteApprovalGate {

    private final PendingMemoryRepository pendingRepository;
    private final MemoryProvider memoryProvider;
    private final AgentProperties properties;
    private volatile boolean enabled;

    public WriteApprovalGate(PendingMemoryRepository pendingRepository,
                             MemoryProvider memoryProvider,
                             AgentProperties properties) {
        this.pendingRepository = pendingRepository;
        this.memoryProvider = memoryProvider;
        this.properties = properties;
        this.enabled = properties.getMemory().isWriteApproval();
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
                case "replace" -> memoryProvider.replace(userId, target, e.getOldText(), e.getContent());
                case "remove" -> memoryProvider.remove(userId, target, e.getOldText());
                default -> log.warn("Unknown pending action: {}", action);
            }
            e.setStatus("approved");
            e.setResolvedAt(Instant.now());
            pendingRepository.save(e);
            log.debug("Approved memory write {}: action={}, target={}", id, action, target);
            return true;
        } catch (Exception ex) {
            log.error("Failed to apply approved write {}: {}", id, ex.getMessage());
            return false;
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