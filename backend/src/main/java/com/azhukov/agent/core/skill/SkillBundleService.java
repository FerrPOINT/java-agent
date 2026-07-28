package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stage 14: Skill Bundle Service.
 * <p>
 * A bundle is a directory containing a SKILL.md file and an optional references/ subdirectory.
 * Bundles are installed as skills via the {@link SkillManager}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillBundleService {

    private final SkillManager skillManager;
    private final AgentProperties properties;

    /**
     * Install a skill bundle by name.
     * <p>
     * Looks for the bundle directory at {@code <workingDirectory>/bundles/<bundleName>}.
     * Reads SKILL.md from the bundle directory and saves it as a skill.
     * If a references/ subdirectory exists, logs that references were found.
     *
     * @param bundleName the name of the bundle to install
     * @throws IllegalArgumentException if the bundle directory doesn't exist
     */
    public void install(String bundleName) {
        String workingDir = properties.getCore().getWorkingDirectory();
        Path bundleDir = Path.of(workingDir, "bundles", bundleName);

        if (!Files.isDirectory(bundleDir)) {
            throw new IllegalArgumentException("Bundle directory not found: " + bundleDir);
        }

        Path skillMd = bundleDir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) {
            throw new IllegalArgumentException("SKILL.md not found in bundle: " + bundleDir);
        }

        try {
            String content = Files.readString(skillMd);
            skillManager.saveSkill(bundleName, content);
            log.info("Installed skill bundle '{}' from {}", bundleName, bundleDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read SKILL.md from bundle: " + bundleDir, e);
        }

        Path referencesDir = bundleDir.resolve("references");
        if (Files.isDirectory(referencesDir)) {
            log.info("Bundle '{}' contains references directory at {}", bundleName, referencesDir);
        }
    }

    /**
     * List all installed skill names (bundles are stored as skills).
     *
     * @return the list of installed skill names
     */
    public List<String> list() {
        return skillManager.listSkillNames();
    }

    /**
     * Uninstall a skill bundle by name.
     *
     * @param bundleName the name of the bundle to uninstall
     */
    public void uninstall(String bundleName) {
        boolean deleted = skillManager.deleteSkill(bundleName);
        log.info("Uninstall bundle '{}': deleted={}", bundleName, deleted);
    }
}