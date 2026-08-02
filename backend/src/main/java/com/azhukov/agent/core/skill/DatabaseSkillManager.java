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
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class DatabaseSkillManager implements SkillManager {

    private final SkillRepository skillRepository;
    private final AgentProperties properties;

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
        for (String subdir : List.of("references", "templates", "scripts")) {
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

    // S3: Validate file path — only references/, templates/, scripts/
    private void validateSupportFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        String normalized = filePath.replace('\\', '/');
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Path traversal not allowed: " + filePath);
        }
        boolean valid = normalized.startsWith("references/") ||
                        normalized.startsWith("templates/") ||
                        normalized.startsWith("scripts/");
        if (!valid) {
            throw new IllegalArgumentException("Support file must be under references/, templates/, or scripts/: " + filePath);
        }
    }
}