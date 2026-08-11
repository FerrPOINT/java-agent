package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class DatabaseSkillManager implements SkillManager {

    private final SkillRepository skillRepository;
    private final AgentProperties properties;

    // ─── Validation constants (ported from Hermes skill_manager_tool.py) ───

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;
    private static final int MAX_SKILL_CONTENT_CHARS = 100_000;  // ~36k tokens
    private static final int MAX_SUPPORT_FILE_BYTES = 1_048_576; // 1 MiB

    /** Filesystem-safe, URL-friendly skill name pattern. */
    private static final Pattern VALID_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /** Subdirectories allowed for writeSupportFile/removeSupportFile. */
    private static final List<String> ALLOWED_SUBDIRS = List.of("references", "templates", "scripts", "assets");

    public DatabaseSkillManager(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
        this.properties = null;
    }

    @Override
    public List<String> listSkillNames() {
        return skillRepository.findAll().stream()
            .filter(e -> !e.isArchived())
            .map(SkillEntity::getName)
            .toList();
    }

    @Override
    public String getSkill(String name) {
        return skillRepository.findByName(name)
            .map(SkillEntity::getContent)
            .orElse(null);
    }

    @Override
    public void saveSkill(String name, String content) {
        saveSkill(name, content, WriteOrigin.FOREGROUND);
    }

    @Override
    public void saveSkill(String name, String content, WriteOrigin origin) {
        // P1-9: Validate skill name
        String nameError = validateName(name);
        if (nameError != null) {
            throw new IllegalArgumentException(nameError);
        }

        // P1-9: Validate content size
        String sizeError = validateContentSize(content);
        if (sizeError != null) {
            throw new IllegalArgumentException(sizeError);
        }

        // P1-9: Validate frontmatter structure
        String frontmatterError = validateFrontmatter(content);
        if (frontmatterError != null) {
            throw new IllegalArgumentException(frontmatterError);
        }

        // P1-9: Security scan — block dangerous content for agent-created skills
        TrustLevel trustLevel = determineTrustLevelForSave(name);
        String scanError = SkillSecurityScanner.scanAndGuard(name, content, trustLevel);
        if (scanError != null) {
            log.warn("Security scan blocked skill save '{}': {}", name, scanError);
            throw new SecurityException(scanError);
        }

        SkillEntity e = skillRepository.findByName(name).orElse(new SkillEntity());
        e.setName(name);
        e.setContent(content);
        e.setUpdatedAt(Instant.now());
        if (e.getCreatedAt() == null) {
            e.setCreatedAt(Instant.now());
        }
        // S6: Set write origin
        e.setWriteOrigin(origin != null ? origin.name() : WriteOrigin.FOREGROUND.name());
        // S7: Update telemetry
        e.setManageCount(e.getManageCount() + 1);
        e.setLastActivityAt(Instant.now());
        // S12: Default trust level for agent-created skills
        if (e.getTrustLevel() == null) {
            e.setTrustLevel(TrustLevel.AGENT_CREATED.name());
        }
        skillRepository.save(e);
    }

    @Override
    public boolean deleteSkill(String name) {
        return skillRepository.findByName(name).map(e -> {
            // P1-9: Pinned-skill guard — prevent deletion of pinned skills
            if (e.isPinned()) {
                log.warn("Skill '{}' is pinned and cannot be deleted by skill manager. " +
                    "Ask the user to unpin it first.", name);
                throw new IllegalStateException(
                    "Skill '" + name + "' is pinned and cannot be deleted. " +
                    "Ask the user to unpin it first."
                );
            }
            skillRepository.delete(e);
            return true;
        }).orElse(false);
    }

    // S7: Telemetry
    @Override
    public void incrementViewCount(String name) {
        skillRepository.findByName(name).ifPresent(e -> {
            e.setViewCount(e.getViewCount() + 1);
            e.setLastActivityAt(Instant.now());
            skillRepository.save(e);
        });
    }

    // S7: Telemetry
    @Override
    public void incrementManageCount(String name) {
        skillRepository.findByName(name).ifPresent(e -> {
            e.setManageCount(e.getManageCount() + 1);
            e.setLastActivityAt(Instant.now());
            skillRepository.save(e);
        });
    }

    // S9: Rich listing
    @Override
    public List<SkillInfo> listSkills() {
        return skillRepository.findAll().stream()
            .filter(e -> !e.isArchived())
            .map(this::toSkillInfo)
            .toList();
    }

    // S9: Get skill info with metadata
    @Override
    public SkillInfo getSkillInfo(String name) {
        return skillRepository.findByName(name)
            .map(this::toSkillInfo)
            .orElse(null);
    }

    private SkillInfo toSkillInfo(SkillEntity e) {
        String category = extractCategory(e.getContent());
        return new SkillInfo(
            e.getName(),
            e.getContent(),
            category,
            e.getUpdatedAt(),
            e.getViewCount(),
            e.getManageCount(),
            e.getLastActivityAt(),
            e.isArchived(),
            e.getTrustLevel() != null ? e.getTrustLevel() : TrustLevel.AGENT_CREATED.name()
        );
    }

    // S9: Parse YAML frontmatter category
    private String extractCategory(String content) {
        if (content == null || content.isBlank()) return "";
        // Try to parse YAML frontmatter
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String yaml = content.substring(3, end);
                for (String line : yaml.lines().toList()) {
                    if (line.trim().startsWith("category:")) {
                        return line.substring("category:".length()).trim();
                    }
                }
            }
        }
        // Fallback: first heading
        for (String line : content.lines().toList()) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "";
    }

    // S3: Write support file
    @Override
    public void writeSupportFile(String skillName, String filePath, String content) {
        validateSupportFilePath(filePath);
        // P1-9: Validate support file content size
        if (content != null && content.getBytes().length > MAX_SUPPORT_FILE_BYTES) {
            throw new IllegalArgumentException(
                "Support file content exceeds " + MAX_SUPPORT_FILE_BYTES + " bytes (limit: 1 MiB)."
            );
        }
        Path dir = getSkillsDir().resolve(skillName);
        Path target = dir.resolve(filePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            log.debug("Wrote support file: {}/{}", skillName, filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write support file: " + e.getMessage(), e);
        }
    }

    // S3: Remove support file
    @Override
    public boolean removeSupportFile(String skillName, String filePath) {
        validateSupportFilePath(filePath);
        Path target = getSkillsDir().resolve(skillName).resolve(filePath);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to remove support file {}/{}: {}", skillName, filePath, e.getMessage());
            return false;
        }
    }

    // S3: Read support file
    @Override
    public String readSupportFile(String skillName, String filePath) {
        validateSupportFilePath(filePath);
        Path target = getSkillsDir().resolve(skillName).resolve(filePath);
        try {
            return Files.exists(target) ? Files.readString(target) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // S3: List support files
    @Override
    public List<String> listSupportFiles(String skillName) {
        Path dir = getSkillsDir().resolve(skillName);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> result = new ArrayList<>();
        for (String subdir : ALLOWED_SUBDIRS) {
            Path sub = dir.resolve(subdir);
            if (Files.isDirectory(sub)) {
                try (Stream<Path> stream = Files.walk(sub, 3)) {
                    stream.filter(Files::isRegularFile)
                        .forEach(p -> result.add(dir.relativize(p).toString().replace('\\', '/')));
                } catch (IOException e) {
                    log.debug("Failed to walk {}/{}", skillName, subdir);
                }
            }
        }
        return result;
    }

    // S2: Archive
    @Override
    public boolean archiveSkill(String name) {
        return skillRepository.findByName(name).map(e -> {
            e.setArchived(true);
            e.setUpdatedAt(Instant.now());
            skillRepository.save(e);
            log.info("Archived skill: {}", name);
            return true;
        }).orElse(false);
    }

    // S2: Unarchive
    @Override
    public boolean unarchiveSkill(String name) {
        return skillRepository.findByName(name).map(e -> {
            e.setArchived(false);
            e.setUpdatedAt(Instant.now());
            skillRepository.save(e);
            log.info("Unarchived skill: {}", name);
            return true;
        }).orElse(false);
    }

    @Override
    public void reload() {
        // Database is always live — no cache to invalidate, but force a query to verify connectivity
        log.info("Reloading skills from database: {} active skills", listSkillNames().size());
    }

    private Path getSkillsDir() {
        if (properties != null && properties.getCore() != null) {
            String wd = properties.getCore().getWorkingDirectory();
            if (wd != null && !wd.isBlank()) {
                return Path.of(wd, "skills");
            }
        }
        return Path.of("skills");
    }

    // ─── P1-9: Validation helpers (ported from Hermes skill_manager_tool.py) ───

    /**
     * Validate a skill name. Returns error message or {@code null} if valid.
     */
    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Skill name is required.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Skill name exceeds " + MAX_NAME_LENGTH + " characters.";
        }
        if (!VALID_NAME_RE.matcher(name).matches()) {
            return "Invalid skill name '" + name + "'. Use lowercase letters, numbers, " +
                "hyphens, dots, and underscores. Must start with a letter or digit.";
        }
        return null;
    }

    /**
     * Validate that SKILL.md content has proper YAML frontmatter with required fields.
     * Returns error message or {@code null} if valid.
     */
    private static String validateFrontmatter(String content) {
        if (content == null || content.isBlank()) {
            return "Content cannot be empty.";
        }
        if (!content.startsWith("---")) {
            return "SKILL.md must start with YAML frontmatter (---). See existing skills for format.";
        }
        // Find closing ---
        int endIdx = content.indexOf("\n---", 3);
        if (endIdx < 0) {
            // Try without newline (content might be just "---\n---")
            return "SKILL.md frontmatter is not closed. Ensure you have a closing '---' line.";
        }
        String yamlContent = content.substring(3, endIdx).trim();

        // Check for required 'name' field
        if (!yamlContent.contains("name:")) {
            return "Frontmatter must include 'name' field.";
        }
        // Check for required 'description' field
        if (!yamlContent.contains("description:")) {
            return "Frontmatter must include 'description' field.";
        }

        // Check body after frontmatter
        int bodyStart = endIdx + 4; // skip "\n---"
        if (bodyStart < content.length()) {
            String body = content.substring(bodyStart).strip();
            if (body.isEmpty()) {
                return "SKILL.md must have content after the frontmatter (instructions, procedures, etc.).";
            }
        }

        return null;
    }

    /**
     * Check that content doesn't exceed the character limit for agent writes.
     */
    private static String validateContentSize(String content) {
        if (content == null) return "Content cannot be null.";
        if (content.length() > MAX_SKILL_CONTENT_CHARS) {
            return "SKILL.md content is " + content.length() + " characters " +
                "(limit: " + MAX_SKILL_CONTENT_CHARS + "). " +
                "Consider splitting into a smaller SKILL.md with supporting files " +
                "in references/ or templates/.";
        }
        return null;
    }

    /**
     * Determine the trust level for a skill being saved.
     * Existing skills keep their trust level; new skills default to AGENT_CREATED.
     */
    private TrustLevel determineTrustLevelForSave(String name) {
        return skillRepository.findByName(name)
            .map(e -> {
                String tl = e.getTrustLevel();
                if (tl != null) {
                    try { return TrustLevel.valueOf(tl); }
                    catch (IllegalArgumentException ignored) {}
                }
                return TrustLevel.AGENT_CREATED;
            })
            .orElse(TrustLevel.AGENT_CREATED);
    }

    // S3: Validate file path — only references/, templates/, scripts/, assets/
    private void validateSupportFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        String normalized = filePath.replace('\\', '/');
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + filePath);
        }
        boolean valid = ALLOWED_SUBDIRS.stream().anyMatch(normalized::startsWith);
        if (!valid) {
            throw new IllegalArgumentException(
                "Support file must be under one of: " + String.join(", ", ALLOWED_SUBDIRS) +
                ". Got: '" + filePath + "'"
            );
        }
    }
}