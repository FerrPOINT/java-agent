package com.azhukov.agent.core.skill;

import java.time.Instant;
import java.util.List;

public interface SkillManager {

    List<String> listSkillNames();

    /**
     * List skill names visible to a specific user.
     * Null userId = all skills (admin). Non-null = personal + shared skills.
     */
    default List<String> listSkillNames(String userId) {
        return listSkillNames();
    }

    String getSkill(String name);

    void saveSkill(String name, String content);

    boolean deleteSkill(String name);

    // S: Delete with absorbed_into — set absorbedInto on the skill entity before deletion
    default boolean deleteSkill(String name, String absorbedInto) {
        return deleteSkill(name);
    }

    // S6: Save with provenance
    default void saveSkill(String name, String content, WriteOrigin origin) {
        saveSkill(name, content);
    }

    /**
     * Finding 4.4: Save with provenance and optional absorbedInto metadata.
     * When absorbedInto is non-null and non-blank, it sets the absorbedInto field
     * on the skill entity (used by the update action).
     */
    default void saveSkill(String name, String content, WriteOrigin origin, String absorbedInto) {
        saveSkill(name, content, origin);
    }

    // S7: Telemetry — increment view count
    default void incrementViewCount(String name) {}

    // S7: Telemetry — increment manage count
    default void incrementManageCount(String name) {}

    // S9: Rich skill metadata for listing
    default List<SkillInfo> listSkills() {
        return listSkillNames().stream()
            .map(name -> new SkillInfo(name, "", "", "", null, 0, 0, null, false, "AGENT_CREATED",
                List.of(), List.of(), false, null))
            .toList();
    }

    // S9: Get skill info (with metadata)
    default SkillInfo getSkillInfo(String name) {
        String content = getSkill(name);
        if (content == null) return null;
        return new SkillInfo(name, content, "", "", null, 0, 0, null, false, "AGENT_CREATED",
            List.of(), List.of(), false, null);
    }

    /**
     * Multi-strategy skill lookup (mirrors Hermes skills_tool.py lines 1000-1078).
     * <ul>
     *   <li>Strategy 1: Direct DB lookup by name</li>
     *   <li>Strategy 2: Recursive filesystem search by directory name</li>
     *   <li>Strategy 3: Frontmatter {@code name:} field match</li>
     * </ul>
     * If multiple strategies find different skills, a collision is reported.
     *
     * @return lookup result containing the resolved skill (or null) and any collision paths
     */
    default SkillLookupResult getSkillInfoMultiStrategy(String name) {
        // Default: just use getSkillInfo
        SkillInfo info = getSkillInfo(name);
        if (info != null) {
            return new SkillLookupResult(info, List.of(), null);
        }
        return new SkillLookupResult(null, List.of(), null);
    }

    // S3: Patch skill content (find-and-replace, all occurrences)
    default boolean patchSkill(String name, String oldText, String newText) {
        return patchSkill(name, oldText, newText, true);
    }

    // S: Patch skill content with replaceAll flag.
    // When replaceAll=false — only first occurrence is replaced.
    // When replaceAll=true — all occurrences are replaced (legacy behaviour).
    default boolean patchSkill(String name, String oldText, String newText, boolean replaceAll) {
        String content = getSkill(name);
        if (content == null) return false;
        String patched;
        if (replaceAll) {
            patched = content.replace(oldText, newText);
        } else {
            patched = content.replaceFirst(
                java.util.regex.Pattern.quote(oldText),
                java.util.regex.Matcher.quoteReplacement(newText)
            );
        }
        if (patched.equals(content)) return false;
        saveSkill(name, patched);
        return true;
    }

    // S: Patch a support file (references/, templates/, scripts/) — find-and-replace
    default boolean patchSupportFile(String skillName, String filePath, String oldText, String newText) {
        return patchSupportFile(skillName, filePath, oldText, newText, true);
    }

    // S: Patch a support file with replaceAll flag
    default boolean patchSupportFile(String skillName, String filePath, String oldText, String newText, boolean replaceAll) {
        String content = readSupportFile(skillName, filePath);
        if (content == null) return false;
        String patched;
        if (replaceAll) {
            patched = content.replace(oldText, newText);
        } else {
            patched = content.replaceFirst(
                java.util.regex.Pattern.quote(oldText),
                java.util.regex.Matcher.quoteReplacement(newText)
            );
        }
        if (patched.equals(content)) return false;
        writeSupportFile(skillName, filePath, patched);
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

    /**
     * List support files for a skill, organized by type (references, templates, scripts, assets).
     * @return a {@link LinkedFiles} structure with separate lists for each type
     */
    default LinkedFiles listSupportFilesByType(String skillName) {
        List<String> all = listSupportFiles(skillName);
        return LinkedFiles.fromFlatList(all);
    }

    // S2: Curator — archive a skill
    default boolean archiveSkill(String name) {
        return false;
    }

    // S2: Curator — unarchive a skill
    default boolean unarchiveSkill(String name) {
        return false;
    }

    // Reload skills — re-scan filesystem, refresh caches, etc.
    default void reload() {}

    // S9: Skill info record — extended with tags, related_skills, disabled, linked_files
    record SkillInfo(
        String name,
        String content,
        String description,
        String category,
        Instant updatedAt,
        int viewCount,
        int manageCount,
        Instant lastActivityAt,
        boolean archived,
        String trustLevel,
        List<String> tags,
        List<String> relatedSkills,
        boolean disabled,
        LinkedFiles linkedFiles
    ) {
        /** Backward-compatible arity (no description) for existing call sites/tests. */
        public SkillInfo(String name, String content, String category, Instant updatedAt,
                         int viewCount, int manageCount, Instant lastActivityAt, boolean archived,
                         String trustLevel, List<String> tags, List<String> relatedSkills,
                         boolean disabled, LinkedFiles linkedFiles) {
            this(name, content, null, category, updatedAt, viewCount, manageCount,
                lastActivityAt, archived, trustLevel, tags, relatedSkills, disabled, linkedFiles);
        }
    }

    /**
     * Result of multi-strategy skill lookup.
     *
     * @param info the resolved skill, or null if not found
     * @param collisionPaths paths of colliding skills (non-empty if multiple skills matched)
     * @param error error message if a collision or other lookup error occurred
     */
    record SkillLookupResult(
        SkillInfo info,
        List<String> collisionPaths,
        String error
    ) {}

    /**
     * Linked files organized by type: references, templates, scripts, assets.
     */
    record LinkedFiles(
        List<String> references,
        List<String> templates,
        List<String> scripts,
        List<String> assets
    ) {
        /**
         * Build a LinkedFiles from a flat list of paths (e.g., "references/ref.md").
         */
        public static LinkedFiles fromFlatList(List<String> files) {
            if (files == null || files.isEmpty()) {
                return new LinkedFiles(List.of(), List.of(), List.of(), List.of());
            }
            List<String> refs = new java.util.ArrayList<>();
            List<String> tmpl = new java.util.ArrayList<>();
            List<String> scr = new java.util.ArrayList<>();
            List<String> ast = new java.util.ArrayList<>();
            for (String f : files) {
                String normalized = f.replace('\\', '/');
                if (normalized.startsWith("references/")) {
                    refs.add(normalized);
                } else if (normalized.startsWith("templates/")) {
                    tmpl.add(normalized);
                } else if (normalized.startsWith("scripts/")) {
                    scr.add(normalized);
                } else if (normalized.startsWith("assets/")) {
                    ast.add(normalized);
                }
            }
            return new LinkedFiles(
                List.copyOf(refs), List.copyOf(tmpl), List.copyOf(scr), List.copyOf(ast)
            );
        }

        /**
         * Return true if all lists are empty.
         */
        public boolean isEmpty() {
            return references.isEmpty() && templates.isEmpty() && scripts.isEmpty() && assets.isEmpty();
        }
    }
}