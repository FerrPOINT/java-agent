package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * P2-14 / H10: Skills auto-sync on startup using a manifest-based approach.
 * <p>
 * Mirrors Hermes' {@code tools/skills_sync.py} behavior: on application startup,
 * copies bundled skills from classpath resources ({@code bundled-skills/}) into
 * the user's skills directory. Uses a manifest file ({@code skills/.bundled-manifest})
 * to track which bundled skills have been synced and their origin hashes, so:
 * <ul>
 *   <li>New bundled skills are copied on first run.</li>
 *   <li>Unchanged bundled skills are skipped.</li>
 *   <li>Skills the user has modified (hash differs from bundled) are preserved.</li>
 *   <li>Skills the user has deleted are not re-added.</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillsSyncService {

    private static final String BUNDLED_SKILLS_CLASSPATH = "classpath*:bundled-skills/**/SKILL.md";
    private static final String MANIFEST_FILENAME = ".bundled-manifest";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentProperties properties;

    /**
     * On startup, sync bundled skills using manifest-based logic.
     */
    @PostConstruct
    void syncBundledSkills() {
        Path skillsDir = resolveSkillsDir();
        try {
            int copied = syncFromClasspath(skillsDir);
            if (copied > 0) {
                log.info("SkillsSyncService: copied {} bundled skill(s) to {}", copied, skillsDir);
            } else {
                log.debug("SkillsSyncService: no new bundled skills to copy to {}", skillsDir);
            }
        } catch (IOException e) {
            log.warn("SkillsSyncService: failed to sync bundled skills to {}: {}", skillsDir, e.getMessage());
        }
    }

    /**
     * H10: Manifest-based sync of bundled SKILL.md files from classpath resources.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Read existing manifest (if any) from {@code skillsDir/.bundled-manifest}</li>
     *   <li>Discover all bundled skills on classpath</li>
     *   <li>For each bundled skill:
     *     <ul>
     *       <li>Compute its origin hash (SHA-256 of bundled content)</li>
     *       <li>If not in manifest and not on disk → copy (new)</li>
     *       <li>If in manifest and hash matches → skip (unchanged)</li>
     *       <li>If in manifest but hash differs → skip (user modified)</li>
     *       <li>If not in manifest but exists on disk → skip (user created or different)</li>
     *     </ul>
     *   </li>
     *   <li>For skills in manifest but not bundled → skip (user deleted, don't re-add)</li>
     *   <li>Write updated manifest with current bundled hashes</li>
     * </ol>
     *
     * @param skillsDir the target skills directory
     * @return number of new skill files copied
     */
    int syncFromClasspath(Path skillsDir) throws IOException {
        // Read existing manifest
        Map<String, String> existingManifest = readManifest(skillsDir);

        // Discover bundled SKILL.md files on the classpath
        List<Resource> bundledResources = discoverBundledSkills();
        if (bundledResources.isEmpty()) {
            log.debug("SkillsSyncService: no bundled skills found on classpath");
            return 0;
        }

        // Create the skills directory if it doesn't exist
        Files.createDirectories(skillsDir);

        // Build the new manifest and perform sync
        Map<String, String> newManifest = new HashMap<>();
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

            // Read bundled content and compute hash
            String bundledContent;
            try (InputStream is = resource.getInputStream()) {
                bundledContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            String originHash = sha256Hex(bundledContent);

            // Use relativePath as the manifest key (e.g. "category/name/SKILL.md")
            newManifest.put(relativePath, originHash);

            Path target = skillsDir.resolve(relativePath);
            String existingHash = existingManifest.get(relativePath);

            if (Files.exists(target)) {
                // File already exists on disk
                if (existingHash == null) {
                    // Not in manifest — could be user-created or pre-existing. Skip.
                    log.debug("SkillsSyncService: skipping existing non-manifest file: {}", relativePath);
                } else if (existingHash.equals(originHash)) {
                    // In manifest and hash matches — unchanged, skip
                    log.debug("SkillsSyncService: skipping unchanged bundled skill: {}", relativePath);
                } else {
                    // In manifest but hash differs — user modified. Skip (preserve user changes).
                    log.debug("SkillsSyncService: skipping user-modified skill: {}", relativePath);
                }
            } else {
                // File doesn't exist on disk
                if (existingHash == null) {
                    // Not in manifest → new bundled skill → copy
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, bundledContent, StandardCharsets.UTF_8);
                    copied++;
                    log.info("SkillsSyncService: copied new bundled skill: {}", relativePath);
                } else {
                    // In manifest but not on disk → user deleted. Skip (don't re-add).
                    log.debug("SkillsSyncService: skipping user-deleted skill: {}", relativePath);
                }
            }
        }

        // Write updated manifest
        writeManifest(skillsDir, newManifest);

        return copied;
    }

    /**
     * Read the manifest file from the skills directory.
     * Returns an empty map if the file doesn't exist or can't be parsed.
     */
    private Map<String, String> readManifest(Path skillsDir) {
        Path manifestPath = skillsDir.resolve(MANIFEST_FILENAME);
        if (!Files.exists(manifestPath)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            Map<String, String> manifest = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            return manifest != null ? manifest : new HashMap<>();
        } catch (IOException e) {
            log.warn("SkillsSyncService: failed to read manifest at {}: {}", manifestPath, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Write the manifest file to the skills directory.
     */
    private void writeManifest(Path skillsDir, Map<String, String> manifest) throws IOException {
        Files.createDirectories(skillsDir);
        Path manifestPath = skillsDir.resolve(MANIFEST_FILENAME);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        Files.writeString(manifestPath, json, StandardCharsets.UTF_8);
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
     * Compute SHA-256 hash of a string, returning a hex string.
     */
    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA-256 hash", e);
        }
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