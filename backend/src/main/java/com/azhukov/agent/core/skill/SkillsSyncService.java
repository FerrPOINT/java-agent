package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P2-14: Skills auto-copy on first run.
 * <p>
 * Mirrors Hermes' {@code tools/skills_sync.py} behavior: on application startup,
 * if the user's skills directory is empty or missing, copies bundled skills from
 * classpath resources ({@code bundled-skills/}) into the user's skills directory.
 * <p>
 * Properties:
 * <ul>
 *   <li>Idempotent — if the skills directory already has skills, does nothing.</li>
 *   <li>Non-destructive — never overwrites existing files.</li>
 *   <li>Logs how many skills were copied.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillsSyncService {

    private static final String BUNDLED_SKILLS_CLASSPATH = "classpath*:bundled-skills/**/SKILL.md";

    private final AgentProperties properties;

    /**
     * On startup, check if the skills directory is empty/missing and seed it
     * with bundled skills from classpath resources.
     */
    @PostConstruct
    void syncBundledSkills() {
        Path skillsDir = resolveSkillsDir();
        try {
            int copied = syncFromClasspath(skillsDir);
            if (copied > 0) {
                log.info("SkillsSyncService: copied {} bundled skill(s) to {}", copied, skillsDir);
            } else if (isDirEmpty(skillsDir)) {
                log.warn("SkillsSyncService: no bundled skills found on classpath and skills directory is empty at {}", skillsDir);
            } else {
                log.debug("SkillsSyncService: skills directory already populated at {}, skipping sync", skillsDir);
            }
        } catch (IOException e) {
            log.warn("SkillsSyncService: failed to sync bundled skills to {}: {}", skillsDir, e.getMessage());
        }
    }

    /**
     * Copy bundled SKILL.md files from classpath resources into the target skills directory.
     * Only copies if the target directory is empty or missing. Never overwrites existing files.
     *
     * @param skillsDir the target skills directory
     * @return number of skill files copied
     */
    int syncFromClasspath(Path skillsDir) throws IOException {
        // If the directory already has SKILL.md files, do nothing (idempotent)
        if (!isDirEmpty(skillsDir)) {
            return 0;
        }

        // Discover bundled SKILL.md files on the classpath
        List<Resource> bundledResources = discoverBundledSkills();
        if (bundledResources.isEmpty()) {
            log.debug("SkillsSyncService: no bundled skills found on classpath");
            return 0;
        }

        // Create the skills directory if it doesn't exist
        Files.createDirectories(skillsDir);

        int copied = 0;
        for (Resource resource : bundledResources) {
            // Extract the relative path: bundled-skills/<category>/<name>/SKILL.md
            String resourceUrl = resource.getURL().toString();
            int idx = resourceUrl.indexOf("bundled-skills/");
            if (idx < 0) {
                log.debug("SkillsSyncService: skipping resource without bundled-skills/ prefix: {}", resourceUrl);
                continue;
            }
            String relativePath = resourceUrl.substring(idx + "bundled-skills/".length());

            Path target = skillsDir.resolve(relativePath);

            // Never overwrite existing files (non-destructive)
            if (Files.exists(target)) {
                log.debug("SkillsSyncService: skipping existing file: {}", target);
                continue;
            }

            // Create parent directories and copy
            Files.createDirectories(target.getParent());
            try (InputStream is = resource.getInputStream()) {
                Files.copy(is, target);
                copied++;
                log.debug("SkillsSyncService: copied bundled skill: {}", relativePath);
            }
        }
        return copied;
    }

    /**
     * Discover all bundled SKILL.md files on the classpath.
     */
    private List<Resource> discoverBundledSkills() {
        List<Resource> resources = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] found = resolver.getResources(BUNDLED_SKILLS_CLASSPATH);
            for (Resource r : found) {
                if (r.exists() && r.isReadable()) {
                    resources.add(r);
                }
            }
        } catch (IOException e) {
            log.debug("SkillsSyncService: failed to discover bundled skills: {}", e.getMessage());
        }
        return resources;
    }

    /**
     * Check if a directory is empty or doesn't exist.
     * A directory is considered empty if it has no SKILL.md files anywhere inside it.
     */
    private boolean isDirEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return true;
        }
        // Check for any SKILL.md files (nested or flat)
        return SkillUtils.iterSkillIndexFiles(dir, "SKILL.md").isEmpty();
    }

    /**
     * Resolve the user's skills directory from configuration.
     */
    private Path resolveSkillsDir() {
        if (properties != null && properties.getCore() != null) {
            String wd = properties.getCore().getWorkingDirectory();
            if (wd != null && !wd.isBlank()) {
                return Path.of(wd, "skills");
            }
        }
        return Path.of("skills");
    }
}