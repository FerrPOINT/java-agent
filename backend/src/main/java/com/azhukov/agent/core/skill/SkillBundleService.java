package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S13: Skill Bundle Service — YAML multi-skill aliases.
 * <p>
 * A bundle is a YAML file that names a set of skills to load together.
 * Invoking a bundle loads every referenced skill's full content.
 * <p>
 * Ported from Hermes' skill_bundles.py (simplified — core bundle support).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillBundleService {

    private final SkillManager skillManager;
    private final AgentProperties properties;

    // S13: Bundle definition
    public record Bundle(
        String name,
        String description,
        List<String> skills,
        String instruction
    ) {}

    private static final Pattern BUNDLE_INVALID_CHARS = Pattern.compile("[^a-z0-9-]");

    /**
     * S13: Install a skill bundle from a YAML config.
     * Loads all skills listed in the bundle config.
     */
    public void installBundle(Bundle bundle) {
        if (bundle == null || bundle.name() == null || bundle.skills() == null) {
            throw new IllegalArgumentException("Bundle must have name and skills list");
        }
        for (String skillName : bundle.skills()) {
            String content = skillManager.getSkill(skillName);
            if (content == null) {
                log.warn("Bundle '{}' references missing skill: {}", bundle.name(), skillName);
                continue;
            }
            // Save as bundle-named skill (concatenated content)
            String bundleContent = buildBundleContent(bundle, skillName, content);
            skillManager.saveSkill(bundle.name() + "/" + skillName, bundleContent);
        }
        log.info("Installed skill bundle '{}' with {} skills", bundle.name(), bundle.skills().size());
    }

    /**
     * S13: Uninstall a bundle — removes all skills in it.
     */
    public void uninstallBundle(String bundleName) {
        for (String skillName : skillManager.listSkillNames()) {
            if (skillName.startsWith(bundleName + "/")) {
                skillManager.deleteSkill(skillName);
            }
        }
        log.info("Uninstalled skill bundle '{}'", bundleName);
    }

    /**
     * S13: Load bundle config from a YAML file.
     */
    public Bundle loadBundleConfig(Path yamlFile) {
        try {
            String yaml = Files.readString(yamlFile);
            return parseBundleYaml(yaml, yamlFile.getFileName().toString().replaceAll("\\.[^.]+$", ""));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read bundle config: " + e.getMessage(), e);
        }
    }

    /**
     * S13: List all bundle files from the bundles directory.
     */
    public List<Bundle> listBundles() {
        Path bundlesDir = getBundlesDir();
        if (!Files.isDirectory(bundlesDir)) {
            return List.of();
        }
        List<Bundle> bundles = new ArrayList<>();
        try (var stream = Files.list(bundlesDir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                .forEach(p -> {
                    try {
                        bundles.add(loadBundleConfig(p));
                    } catch (Exception e) {
                        log.warn("Failed to load bundle config {}: {}", p, e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to list bundles: {}", e.getMessage());
        }
        return bundles;
    }

    /**
     * S13: Save a bundle config to a YAML file.
     */
    public void saveBundleConfig(Bundle bundle) {
        Path bundlesDir = getBundlesDir();
        try {
            Files.createDirectories(bundlesDir);
            Path file = bundlesDir.resolve(slugify(bundle.name()) + ".yaml");
            String yaml = buildBundleYaml(bundle);
            Files.writeString(file, yaml);
            log.info("Saved bundle config: {}", file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save bundle config: " + e.getMessage(), e);
        }
    }

    /**
     * S13: Delete a bundle config file.
     */
    public boolean deleteBundleConfig(String bundleName) {
        Path file = getBundlesDir().resolve(slugify(bundleName) + ".yaml");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * S13: Build the combined content for a bundle skill entry.
     */
    private String buildBundleContent(Bundle bundle, String skillName, String content) {
        StringBuilder sb = new StringBuilder();
        if (bundle.instruction() != null && !bundle.instruction().isBlank()) {
            sb.append(bundle.instruction()).append("\n\n");
        }
        sb.append("--- Skill: ").append(skillName).append(" ---\n\n");
        sb.append(content);
        return sb.toString();
    }

    /**
     * S13: Parse a simple YAML bundle config.
     */
    private Bundle parseBundleYaml(String yaml, String fallbackName) {
        String name = fallbackName;
        String description = "";
        List<String> skills = new ArrayList<>();
        String instruction = "";
        boolean inInstruction = false;

        for (String line : yaml.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;

            if (trimmed.startsWith("name:")) {
                name = unquote(trimmed.substring(5).trim());
            } else if (trimmed.startsWith("description:")) {
                description = unquote(trimmed.substring(12).trim());
            } else if (trimmed.startsWith("instruction:")) {
                String val = unquote(trimmed.substring(12).trim());
                if (val.equals("|")) {
                    inInstruction = true;
                } else {
                    instruction = val;
                }
            } else if (inInstruction) {
                if (trimmed.isEmpty() || trimmed.startsWith("- ") || trimmed.matches("^[a-z]+:.*")) {
                    inInstruction = false;
                    if (trimmed.startsWith("- ")) {
                        skills.add(trimmed.substring(2).trim());
                    }
                } else {
                    instruction += (instruction.isEmpty() ? "" : "\n") + line;
                }
            } else if (trimmed.startsWith("- ")) {
                skills.add(trimmed.substring(2).trim());
            }
        }

        return new Bundle(name, description, skills, instruction);
    }

    /**
     * S13: Build YAML for a bundle config.
     */
    private String buildBundleYaml(Bundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(quote(bundle.name())).append("\n");
        sb.append("description: ").append(quote(bundle.description())).append("\n");
        sb.append("skills:\n");
        for (String skill : bundle.skills()) {
            sb.append("  - ").append(skill).append("\n");
        }
        if (bundle.instruction() != null && !bundle.instruction().isBlank()) {
            sb.append("instruction: |\n");
            for (String line : bundle.instruction().lines().toList()) {
                sb.append("  ").append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String slugify(String name) {
        String slug = name.toLowerCase().replace(" ", "-").replace("_", "-").replaceAll("[^a-z0-9-]", "");
        return slug.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }

    private String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        if (s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        return s;
    }

    private String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private Path getBundlesDir() {
        if (properties != null && properties.getCore() != null) {
            String wd = properties.getCore().getWorkingDirectory();
            if (wd != null && !wd.isBlank()) {
                return Path.of(wd, "skill-bundles");
            }
        }
        return Path.of("skill-bundles");
    }

    // Legacy compatibility
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
    }

    public List<String> list() {
        return skillManager.listSkillNames();
    }

    public void uninstall(String bundleName) {
        boolean deleted = skillManager.deleteSkill(bundleName);
        log.info("Uninstall bundle '{}': deleted={}", bundleName, deleted);
    }
}