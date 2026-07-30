package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.CuratorSnapshotRepository;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S15: Curator backup — snapshot and rollback of skills state.
 * <p>
 * Ported from Hermes' curator_backup.py. Before any curator mutation,
 * creates a backup snapshot (DB record) of the skills state. Supports
 * rollback to a previous snapshot.
 */
@Service
@Slf4j
public class CuratorBackupService {

    private static final int DEFAULT_KEEP = 5;

    private final SkillRepository skillRepository;
    private final CuratorSnapshotRepository snapshotRepository;

    public CuratorBackupService(SkillRepository skillRepository, CuratorSnapshotRepository snapshotRepository) {
        this.skillRepository = skillRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Create a snapshot of the current skills state before a curator mutation.
     *
     * @param reason why the snapshot is being taken (e.g. "curator-cycle")
     * @return the created snapshot, or null if the snapshot was skipped
     */
    public CuratorSnapshot createSnapshot(String reason) {
        try {
            List<SkillEntity> allSkills = skillRepository.findAllBy();
            if (allSkills.isEmpty()) {
                log.debug("No skills to snapshot — skipping");
                return null;
            }

            CuratorSnapshotEntity entity = new CuratorSnapshotEntity();
            entity.setReason(reason);
            entity.setCreatedAt(Instant.now());
            entity.setSkillCount(allSkills.size());

            // Serialize skills state as JSON-like text
            StringBuilder data = new StringBuilder();
            for (SkillEntity skill : allSkills) {
                data.append("=== ").append(skill.getName()).append(" ===\n");
                data.append("archived: ").append(skill.isArchived()).append("\n");
                data.append("trustLevel: ").append(skill.getTrustLevel()).append("\n");
                data.append("updatedAt: ").append(skill.getUpdatedAt()).append("\n");
                data.append("lastActivityAt: ").append(skill.getLastActivityAt()).append("\n");
                data.append("---CONTENT---\n");
                data.append(skill.getContent() != null ? skill.getContent() : "").append("\n");
                data.append("---END---\n");
            }
            entity.setSnapshotData(data.toString());

            entity = snapshotRepository.save(entity);
            log.info("Curator snapshot created: {} (reason: {}, skills: {})", entity.getId(), reason, allSkills.size());

            // Prune old snapshots
            pruneOldSnapshots();

            return new CuratorSnapshot(entity.getId(), entity.getReason(), entity.getCreatedAt(),
                entity.getSkillCount());
        } catch (Exception e) {
            log.error("Failed to create curator snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * List all available snapshots, newest first.
     */
    public List<CuratorSnapshot> listSnapshots() {
        return snapshotRepository.findAllByOrderByCreatedAtDesc().stream()
            .map(e -> new CuratorSnapshot(e.getId(), e.getReason(), e.getCreatedAt(), e.getSkillCount()))
            .toList();
    }

    /**
     * Rollback to a specific snapshot by ID.
     * Restores skills state from the snapshot data.
     *
     * @param snapshotId the snapshot to rollback to
     * @return true if rollback succeeded
     */
    public boolean rollback(UUID snapshotId) {
        try {
            CuratorSnapshotEntity snapshot = snapshotRepository.findById(snapshotId).orElse(null);
            if (snapshot == null) {
                log.warn("Snapshot not found: {}", snapshotId);
                return false;
            }

            // Parse the snapshot data and restore skills
            String data = snapshot.getSnapshotData();
            if (data == null || data.isBlank()) {
                log.warn("Snapshot {} has no data", snapshotId);
                return false;
            }

            // Take a pre-rollback snapshot so the rollback itself is undoable
            createSnapshot("pre-rollback-" + snapshotId);

            // Parse and restore
            List<ParsedSkill> parsed = parseSnapshotData(data);
            for (ParsedSkill ps : parsed) {
                SkillEntity existing = skillRepository.findByName(ps.name).orElse(null);
                if (existing != null) {
                    existing.setArchived(ps.archived);
                    existing.setTrustLevel(ps.trustLevel);
                    existing.setContent(ps.content);
                    existing.setUpdatedAt(Instant.now());
                    skillRepository.save(existing);
                }
            }

            log.info("Curator rollback to snapshot {} complete — {} skills restored", snapshotId, parsed.size());
            return true;
        } catch (Exception e) {
            log.error("Rollback to snapshot {} failed: {}", snapshotId, e.getMessage());
            return false;
        }
    }

    /**
     * Get a specific snapshot by ID.
     */
    public CuratorSnapshot getSnapshot(UUID id) {
        return snapshotRepository.findById(id)
            .map(e -> new CuratorSnapshot(e.getId(), e.getReason(), e.getCreatedAt(), e.getSkillCount()))
            .orElse(null);
    }

    /**
     * Delete a snapshot by ID.
     */
    public void deleteSnapshot(UUID id) {
        snapshotRepository.deleteById(id);
        log.info("Deleted curator snapshot: {}", id);
    }

    // ── Internal ───────────────────────────────────────────────────────

    private void pruneOldSnapshots() {
        List<CuratorSnapshotEntity> all = snapshotRepository.findAllByOrderByCreatedAtDesc();
        if (all.size() <= DEFAULT_KEEP) {
            return;
        }
        for (int i = DEFAULT_KEEP; i < all.size(); i++) {
            snapshotRepository.delete(all.get(i));
        }
        log.info("Pruned {} old curator snapshots", all.size() - DEFAULT_KEEP);
    }

    private List<ParsedSkill> parseSnapshotData(String data) {
        List<ParsedSkill> result = new ArrayList<>();
        String[] blocks = data.split("=== ");
        for (String block : blocks) {
            if (block.isBlank()) continue;
            String name = block.split(" ===")[0].trim();
            if (name.isEmpty()) continue;
            boolean archived = false;
            String trustLevel = "AGENT_CREATED";
            String content = "";
            String[] lines = block.split("\n");
            boolean inContent = false;
            StringBuilder contentBuilder = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("archived: ")) {
                    archived = Boolean.parseBoolean(line.substring("archived: ".length()).trim());
                } else if (line.startsWith("trustLevel: ")) {
                    trustLevel = line.substring("trustLevel: ".length()).trim();
                } else if (line.equals("---CONTENT---")) {
                    inContent = true;
                } else if (line.equals("---END---")) {
                    inContent = false;
                    content = contentBuilder.toString().trim();
                    contentBuilder.setLength(0);
                } else if (inContent) {
                    contentBuilder.append(line).append("\n");
                }
            }
            result.add(new ParsedSkill(name, archived, trustLevel, content));
        }
        return result;
    }

    private record ParsedSkill(String name, boolean archived, String trustLevel, String content) {}

    /**
     * Snapshot summary record.
     */
    public record CuratorSnapshot(UUID id, String reason, Instant createdAt, int skillCount) {}
}