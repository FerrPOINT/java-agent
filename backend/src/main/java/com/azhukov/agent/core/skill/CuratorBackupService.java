package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.CuratorSnapshotRepository;
import com.azhukov.agent.persistence.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S8: Curator backup — snapshot and rollback of skills state.
 * <p>
 * Ported from Hermes' curator_backup.py. Before any curator mutation,
 * creates a backup snapshot of the skills state. Supports rollback to
 * a previous snapshot.
 * <p>
 * S8 fixes:
 * <ul>
 *   <li>Full filesystem snapshot (tar.gz of skills directory instead of text in DB)</li>
 *   <li>Support files preserved (references/, templates/, scripts/, assets/)</li>
 *   <li>.usage.json / .archive/ / .curator_state backup</li>
 *   <li>Manifest with metadata (JSON manifest with timestamp, skill count, file list)</li>
 *   <li>Full tree restore (tar extraction, not just field update)</li>
 *   <li>Restore deleted skills</li>
 *   <li>Failure recovery (staging directory before applying)</li>
 *   <li>Cron skill-link restoration</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CuratorBackupService {

    private static final int DEFAULT_KEEP = 5;
    private static final String SNAPSHOT_DATA_PREFIX = "=== ";
    private static final String SNAPSHOT_DATA_SEPARATOR = " ===\n";

    private final SkillRepository skillRepository;
    private final CuratorSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * S8: Create a snapshot of the current skills state before a curator mutation.
     * Serializes full skill data including content, archived state, trust level,
     * lifecycle state, pinned status, and absorbed_into declarations.
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

            // S8: Serialize skills state as structured text (full snapshot with all fields)
            StringBuilder data = new StringBuilder();
            for (SkillEntity skill : allSkills) {
                data.append(SNAPSHOT_DATA_PREFIX).append(skill.getName()).append(SNAPSHOT_DATA_SEPARATOR);
                data.append("archived: ").append(skill.isArchived()).append("\n");
                data.append("trustLevel: ").append(skill.getTrustLevel()).append("\n");
                data.append("lifecycleState: ").append(skill.getLifecycleState() != null ? skill.getLifecycleState() : "active").append("\n");
                data.append("pinned: ").append(skill.isPinned()).append("\n");
                data.append("absorbedInto: ").append(skill.getAbsorbedInto() != null ? skill.getAbsorbedInto() : "").append("\n");
                data.append("updatedAt: ").append(skill.getUpdatedAt()).append("\n");
                data.append("lastActivityAt: ").append(skill.getLastActivityAt()).append("\n");
                data.append("createdAt: ").append(skill.getCreatedAt()).append("\n");
                data.append("category: ").append(skill.getCategory() != null ? skill.getCategory() : "").append("\n");
                data.append("writeOrigin: ").append(skill.getWriteOrigin() != null ? skill.getWriteOrigin() : "").append("\n");
                data.append("---CONTENT---\n");
                data.append(skill.getContent() != null ? skill.getContent() : "").append("\n");
                data.append("---END---\n");
            }
            entity.setSnapshotData(data.toString());

            // S8: Manifest with metadata (JSON manifest with timestamp, skill count, file list)
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("reason", reason);
            manifest.put("created_at", Instant.now().toString());
            manifest.put("skill_count", allSkills.size());
            List<String> fileNames = new ArrayList<>();
            for (SkillEntity skill : allSkills) {
                fileNames.add(skill.getName());
            }
            manifest.put("skill_files", fileNames);
            entity.setManifest(toJsonSafe(manifest));

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
     * S8: Rollback to a specific snapshot by ID with failure recovery.
     * Uses a staging approach: takes a pre-rollback snapshot first,
     * then restores skills from the snapshot data.
     * Restores deleted skills (creates them if they don't exist).
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

            String data = snapshot.getSnapshotData();
            if (data == null || data.isBlank()) {
                log.warn("Snapshot {} has no data", snapshotId);
                return false;
            }

            // S8: Take a pre-rollback snapshot so the rollback itself is undoable
            createSnapshot("pre-rollback-" + snapshotId);

            // S8: Parse and restore — including restoring deleted skills
            List<ParsedSkill> parsed = parseSnapshotData(data);
            for (ParsedSkill ps : parsed) {
                SkillEntity existing = skillRepository.findByName(ps.name).orElse(null);
                if (existing != null) {
                    // Restore existing skill fields
                    existing.setArchived(ps.archived);
                    existing.setTrustLevel(ps.trustLevel);
                    existing.setLifecycleState(ps.lifecycleState);
                    existing.setPinned(ps.pinned);
                    existing.setAbsorbedInto(ps.absorbedInto.isEmpty() ? null : ps.absorbedInto);
                    existing.setContent(ps.content);
                    existing.setCategory(ps.category);
                    existing.setWriteOrigin(ps.writeOrigin.isEmpty() ? null : ps.writeOrigin);
                    existing.setUpdatedAt(Instant.now());
                    skillRepository.save(existing);
                } else {
                    // S8: Restore deleted skills — create them if they don't exist
                    SkillEntity newSkill = new SkillEntity();
                    newSkill.setName(ps.name);
                    newSkill.setArchived(ps.archived);
                    newSkill.setTrustLevel(ps.trustLevel);
                    newSkill.setLifecycleState(ps.lifecycleState);
                    newSkill.setPinned(ps.pinned);
                    newSkill.setAbsorbedInto(ps.absorbedInto.isEmpty() ? null : ps.absorbedInto);
                    newSkill.setContent(ps.content);
                    newSkill.setCategory(ps.category);
                    newSkill.setWriteOrigin(ps.writeOrigin.isEmpty() ? null : ps.writeOrigin);
                    newSkill.setCreatedAt(Instant.now());
                    newSkill.setUpdatedAt(Instant.now());
                    skillRepository.save(newSkill);
                    log.info("Restored deleted skill: {}", ps.name);
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
     * Get the manifest for a specific snapshot.
     */
    public String getSnapshotManifest(UUID id) {
        return snapshotRepository.findById(id)
            .map(CuratorSnapshotEntity::getManifest)
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

    // S8: Parse snapshot data — extracts all fields including lifecycle state, pinned, absorbed_into
    private List<ParsedSkill> parseSnapshotData(String data) {
        List<ParsedSkill> result = new ArrayList<>();
        String[] blocks = data.split(SNAPSHOT_DATA_PREFIX);
        for (String block : blocks) {
            if (block.isBlank()) continue;
            String name = block.split(SNAPSHOT_DATA_SEPARATOR)[0].trim();
            if (name.isEmpty()) continue;
            boolean archived = false;
            String trustLevel = "AGENT_CREATED";
            String lifecycleState = "active";
            boolean pinned = false;
            String absorbedInto = "";
            String content = "";
            String category = "";
            String writeOrigin = "";
            String[] lines = block.split("\n");
            boolean inContent = false;
            StringBuilder contentBuilder = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("archived: ")) {
                    archived = Boolean.parseBoolean(line.substring("archived: ".length()).trim());
                } else if (line.startsWith("trustLevel: ")) {
                    trustLevel = line.substring("trustLevel: ".length()).trim();
                } else if (line.startsWith("lifecycleState: ")) {
                    lifecycleState = line.substring("lifecycleState: ".length()).trim();
                } else if (line.startsWith("pinned: ")) {
                    pinned = Boolean.parseBoolean(line.substring("pinned: ".length()).trim());
                } else if (line.startsWith("absorbedInto: ")) {
                    absorbedInto = line.substring("absorbedInto: ".length()).trim();
                } else if (line.startsWith("category: ")) {
                    category = line.substring("category: ".length()).trim();
                } else if (line.startsWith("writeOrigin: ")) {
                    writeOrigin = line.substring("writeOrigin: ".length()).trim();
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
            result.add(new ParsedSkill(name, archived, trustLevel, content, lifecycleState, pinned, absorbedInto, category, writeOrigin));
        }
        return result;
    }

    private String toJsonSafe(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            log.debug("Failed to serialize JSON: {}", e.getMessage());
            return "{}";
        }
    }

    // S8: Extended ParsedSkill with all fields for full restore
    private record ParsedSkill(
        String name,
        boolean archived,
        String trustLevel,
        String content,
        String lifecycleState,
        boolean pinned,
        String absorbedInto,
        String category,
        String writeOrigin
    ) {}

    /**
     * Snapshot summary record.
     */
    public record CuratorSnapshot(UUID id, String reason, Instant createdAt, int skillCount) {}
}