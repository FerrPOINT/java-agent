package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import com.azhukov.agent.persistence.repository.PendingMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

/**
 * Write-approval gate: if enabled, memory writes are staged to a pending queue
 * instead of being applied directly. Users can approve or reject pending writes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteApprovalGate {

    private final PendingMemoryRepository pendingRepository;
    private final MemoryProvider memoryProvider;
    private final AgentProperties properties;
    // rev-87: optional SkillManageTool for replaying staged skill writes.
    // ObjectProvider to avoid a constructor-signature break for tests.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.tools.memory.SkillManageTool> skillManageToolProvider;
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
                case "replace" -> memoryProvider.replace(userId, target, e.getOldText(), e.getContent());
                case "remove" -> memoryProvider.remove(userId, target, e.getOldText());
                case "batch" -> applyBatchFromStagedJson(userId, target, e.getContent());
                case "skill_manage" -> applySkillManageFromStagedJson(e.getContent());
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
            org.springframework.transaction.interceptor.TransactionAspectSupport
                .currentTransactionStatus().setRollbackOnly();
            return false;
        }
    }

    /**
     * Replay a staged skill_manage write (Hermes apply_skill_pending parity):
     * the full tool args were serialized to JSON in the pending row's content.
     * Delegates to the real SkillManageTool via a replay bypass — the gate is
     * skipped because the write was already approved.
     */
    private void applySkillManageFromStagedJson(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            throw new IllegalStateException(
                "Staged skill_manage has no serialized args — the write cannot be replayed");
        }
        com.azhukov.agent.tools.memory.SkillManageTool tool = skillManageToolProvider != null
            ? skillManageToolProvider.getIfAvailable() : null;
        if (tool == null) {
            throw new IllegalStateException("SkillManageTool unavailable — cannot replay staged skill write");
        }
        try {
            // Replay bypass: run with the gate disabled for THIS call only.
            // The staged write was approved by the user; re-gating it would
            // stage it again forever (Hermes uses a ContextVar bypass — here
            // we temporarily flip the volatile enabled flag on this thread's
            // behalf; approve() is user-triggered and single-threaded per row.
            boolean wasEnabled = enabled;
            try {
                setApproval(false);
                com.azhukov.agent.core.model.ToolResult result =
                    tool.execute(argsJson, null, null);
                if (result == null || !result.success()) {
                    throw new IllegalStateException("skill_manage replay failed: "
                        + (result != null ? result.content() : "null result"));
                }
            } finally {
                setApproval(wasEnabled);
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to replay staged skill_manage: " + ex.getMessage(), ex);
        }
    }

    /**
     * Replay a staged batch write (Hermes apply_memory_pending "batch" parity):
     * the operations were serialized to JSON in the pending row's content.
     */
    private void applyBatchFromStagedJson(String userId, String target, String opsJson) {
        if (opsJson == null || opsJson.isBlank()) {
            throw new IllegalStateException(
                "Staged batch has no serialized operations — the write cannot be replayed");
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            List<com.fasterxml.jackson.databind.node.ObjectNode> ops = mapper.readValue(
                opsJson,
                mapper.getTypeFactory().constructCollectionType(List.class, com.fasterxml.jackson.databind.node.ObjectNode.class));
            List<MemoryProvider.MemoryBatchOperation> batch = new java.util.ArrayList<>(ops.size());
            for (var node : ops) {
                batch.add(new MemoryProvider.MemoryBatchOperation(
                    node.path("action").asText(null),
                    node.path("content").asText(null),
                    node.path("old_text").asText(null)));
            }
            String error = memoryProvider.applyBatch(userId, target, batch, java.util.Map.of());
            if (error != null) {
                throw new IllegalStateException(error);
            }
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to replay staged batch: " + ex.getMessage(), ex);
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