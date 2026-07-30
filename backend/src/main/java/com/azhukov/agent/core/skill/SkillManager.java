package com.azhukov.agent.core.skill;

import java.time.Instant;
import java.util.List;

public interface SkillManager {

    List<String> listSkillNames();

    String getSkill(String name);

    void saveSkill(String name, String content);

    boolean deleteSkill(String name);

    // S6: Save with provenance
    default void saveSkill(String name, String content, WriteOrigin origin) {
        saveSkill(name, content);
    }

    // S7: Telemetry — increment view count
    default void incrementViewCount(String name) {}

    // S7: Telemetry — increment manage count
    default void incrementManageCount(String name) {}

    // S9: Rich skill metadata for listing
    default List<SkillInfo> listSkills() {
        return listSkillNames().stream()
            .map(name -> new SkillInfo(name, "", "", null, 0, 0, null, false, "AGENT_CREATED"))
            .toList();
    }

    // S9: Get skill info (with metadata)
    default SkillInfo getSkillInfo(String name) {
        String content = getSkill(name);
        if (content == null) return null;
        return new SkillInfo(name, content, "", null, 0, 0, null, false, "AGENT_CREATED");
    }

    // S3: Patch skill content (find-and-replace)
    default boolean patchSkill(String name, String oldText, String newText) {
        String content = getSkill(name);
        if (content == null) return false;
        String patched = content.replace(oldText, newText);
        if (patched.equals(content)) return false;
        saveSkill(name, patched);
        return true;
    }

    // S3: Write support file (references/, templates/, scripts/)
    default void writeSupportFile(String skillName, String filePath, String content) {
        throw new UnsupportedOperationException("writeSupportFile not supported");
    }

    // S3: Remove support file
    default boolean removeSupportFile(String skillName, String filePath) {
        throw new UnsupportedOperationException("removeSupportFile not supported");
    }

    // S3: Read support file
    default String readSupportFile(String skillName, String filePath) {
        return null;
    }

    // S3: List support files for a skill
    default List<String> listSupportFiles(String skillName) {
        return List.of();
    }

    // S2: Curator — archive a skill
    default boolean archiveSkill(String name) {
        return false;
    }

    // S2: Curator — unarchive a skill
    default boolean unarchiveSkill(String name) {
        return false;
    }

    // S9: Skill info record
    record SkillInfo(
        String name,
        String content,
        String category,
        Instant updatedAt,
        int viewCount,
        int manageCount,
        Instant lastActivityAt,
        boolean archived,
        String trustLevel
    ) {}
}