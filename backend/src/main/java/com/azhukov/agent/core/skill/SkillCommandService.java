package com.azhukov.agent.core.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * S2: Slash command system for skills — scans skill directories for /command definitions,
 * resolves user-typed commands, and builds invocation messages.
 * <p>
 * Ported from Hermes' skill_commands.py.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillCommandService {

    private final SkillUtils skillUtils;
    private final SkillPreprocessor preprocessor;
    private final SkillBundleService bundleService;

    // S2: Cached skill commands mapping: "/slug" -> SkillCommandInfo
    private volatile Map<String, SkillCommandInfo> skillCommands = new LinkedHashMap<>();
    private volatile String cachedPlatform = null;

    // S2: Skill command info record
    public record SkillCommandInfo(
        String name,
        String description,
        String skillMdPath,
        String skillDir
    ) {}

    /**
     * S2: Scan skill directories for /command definitions in frontmatter.
     * Normalizes names to /slug, filters by platform/env, respects disabled skills.
     */
    public synchronized Map<String, SkillCommandInfo> scanSkillCommands() {
        cachedPlatform = resolvePlatform();
        Map<String, SkillCommandInfo> commands = new LinkedHashMap<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> disabled = skillUtils.getDisabledSkillNames(cachedPlatform);

        for (Path scanDir : skillUtils.getAllSkillsDirs()) {
            if (!Files.isDirectory(scanDir)) continue;
            for (Path skillMd : SkillUtils.iterSkillIndexFiles(scanDir, "SKILL.md")) {
                if (SkillUtils.isExcludedSkillPath(skillMd)) continue;
                try {
                    String content = Files.readString(skillMd);
                    SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
                    Map<String, Object> fm = fr.frontmatter();

                    // Skip skills incompatible with current platform
                    if (!SkillUtils.skillMatchesPlatform(fm)) continue;
                    // Skip skills not relevant to current environment (offer-time only)
                    if (!SkillUtils.skillMatchesEnvironment(fm)) continue;

                    Object nameObj = fm.get("name");
                    String name = nameObj != null ? String.valueOf(nameObj) : skillMd.getParent().getFileName().toString();
                    if (seenNames.contains(name)) continue;
                    if (disabled.contains(name)) continue;

                    Object descObj = fm.get("description");
                    String description = descObj != null ? String.valueOf(descObj) : "";
                    if (description.isEmpty()) {
                        // Fallback: first non-heading line in body
                        for (String line : fr.body().strip().split("\n")) {
                            String trimmed = line.trim();
                            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                                description = trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
                                break;
                            }
                        }
                    }

                    seenNames.add(name);
                    String cmdName = SkillUtils.slugify(name);
                    if (cmdName.isEmpty()) continue;

                    commands.put("/" + cmdName, new SkillCommandInfo(
                        name,
                        description.isEmpty() ? "Invoke the " + name + " skill" : description,
                        skillMd.toString(),
                        skillMd.getParent().toString()
                    ));
                } catch (Exception e) {
                    log.debug("Failed to scan skill file: {}", skillMd);
                }
            }
        }

        skillCommands = commands;
        return commands;
    }

    /**
     * S2: Return current skill commands mapping, scanning first if empty or platform changed.
     */
    public Map<String, SkillCommandInfo> getSkillCommands() {
        String currentPlatform = resolvePlatform();
        if (skillCommands.isEmpty() || !Objects.equals(cachedPlatform, currentPlatform)) {
            scanSkillCommands();
        }
        return skillCommands;
    }

    /**
     * S2: Resolve a user-typed /command to its canonical skill command key.
     * Handles _/- interchangeability.
     */
    public String resolveSkillCommandKey(String command) {
        if (command == null || command.isBlank()) return null;
        String cmdKey = "/" + command.replace("_", "-");
        return getSkillCommands().containsKey(cmdKey) ? cmdKey : null;
    }

    /**
     * S2: Build the user message content for a skill slash command invocation.
     * Loads skill, applies preprocessing, injects activation note + config + support files + setup hints.
     */
    public String buildSkillInvocationMessage(String cmdKey, String userInstruction, String sessionId) {
        return buildSkillInvocationMessage(cmdKey, userInstruction, sessionId, "");
    }

    /**
     * S2: Build the user message content for a skill slash command invocation.
     */
    public String buildSkillInvocationMessage(String cmdKey, String userInstruction, String sessionId, String runtimeNote) {
        // S6: Check bundles first — bundles win over same-named skills
        String bundleKey = bundleService.resolveBundleCommandKey(cmdKey.startsWith("/") ? cmdKey.substring(1) : cmdKey);
        if (bundleKey != null) {
            var bundleResult = bundleService.buildBundleInvocationMessage(bundleKey, userInstruction, sessionId);
            if (bundleResult != null) {
                return bundleResult.message();
            }
        }

        Map<String, SkillCommandInfo> commands = getSkillCommands();
        SkillCommandInfo info = commands.get(cmdKey);
        if (info == null) return null;

        // Load skill from directory
        Path skillDir = Path.of(info.skillDir());
        Path skillMd = Path.of(info.skillMdPath());
        if (!Files.exists(skillMd)) return null;

        try {
            String content = Files.readString(skillMd);
            return buildSkillMessage(content, skillDir, info.name(), userInstruction, sessionId, runtimeNote);
        } catch (IOException e) {
            log.warn("Failed to load skill {}: {}", info.name(), e.getMessage());
            return null;
        }
    }

    /**
     * S2: Build a formatted skill message from loaded content.
     */
    String buildSkillMessage(String content, Path skillDir, String skillName,
                             String userInstruction, String sessionId, String runtimeNote) {
        // Apply preprocessing (template vars + inline shell)
        content = preprocessor.preprocess(content, sessionId, skillDir != null ? skillDir.toString() : null);

        List<String> parts = new ArrayList<>();
        String activationNote = "[IMPORTANT: The user has invoked the \"" + skillName +
            "\" skill, indicating they want you to follow its instructions. The full skill content is loaded below.]";
        parts.add(activationNote);
        parts.add("");
        parts.add(content.strip());

        // Inject skill directory
        if (skillDir != null) {
            parts.add("");
            parts.add("[Skill directory: " + skillDir + "]");
            parts.add("Resolve any relative paths in this skill (e.g. `scripts/foo.js`, " +
                "`templates/config.yaml`) against that directory, then run them " +
                "with the terminal tool using the absolute path.");
        }

        // S6: Inject resolved skill config values
        SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
        List<SkillUtils.SkillConfigVar> configVars = SkillUtils.extractSkillConfigVars(fr.frontmatter());
        if (!configVars.isEmpty()) {
            Map<String, Object> resolved = skillUtils.resolveSkillConfigValues(configVars);
            if (!resolved.isEmpty()) {
                parts.add("");
                parts.add("[Skill config (from config.yaml):]");
                for (var entry : resolved.entrySet()) {
                    String displayVal = entry.getValue() != null ? String.valueOf(entry.getValue()) : "(not set)";
                    parts.add("  " + entry.getKey() + " = " + displayVal);
                }
                parts.add("]");
            }
        }

        // Inject supporting files hints
        if (skillDir != null) {
            List<String> supporting = discoverSupportingFiles(skillDir);
            if (!supporting.isEmpty()) {
                parts.add("");
                parts.add("[This skill has supporting files:]");
                for (String sf : supporting) {
                    parts.add("- " + sf + "  ->  " + skillDir.resolve(sf));
                }
                String skillViewTarget = skillDir.getFileName().toString();
                parts.add("\nLoad any of these with skill_view(name=\"" + skillViewTarget +
                    "\", file_path=\"<path>\"), or run scripts directly by absolute path.");
            }
        }

        // User instruction
        if (userInstruction != null && !userInstruction.isBlank()) {
            parts.add("");
            parts.add("The user has provided the following instruction alongside the skill invocation: " + userInstruction);
        }

        // Runtime note
        if (runtimeNote != null && !runtimeNote.isBlank()) {
            parts.add("");
            parts.add("[Runtime note: " + runtimeNote + "]");
        }

        return String.join("\n", parts);
    }

    /**
     * S2: Discover supporting files in a skill directory (references/, templates/, scripts/, assets/).
     */
    private List<String> discoverSupportingFiles(Path skillDir) {
        List<String> supporting = new ArrayList<>();
        for (String subdir : List.of("references", "templates", "scripts", "assets")) {
            Path subdirPath = skillDir.resolve(subdir);
            if (Files.isDirectory(subdirPath)) {
                try (var stream = Files.walk(subdirPath)) {
                    stream.filter(Files::isRegularFile)
                        .forEach(f -> {
                            String rel = skillDir.relativize(f).toString().replace('\\', '/');
                            supporting.add(rel);
                        });
                } catch (IOException e) {
                    log.debug("Failed to walk {}/{}", skillDir, subdir);
                }
            }
        }
        Collections.sort(supporting);
        return supporting;
    }

    /**
     * S2: Reload skills — re-scan and return added/removed/unchanged diff.
     */
    public ReloadDiff reloadSkills() {
        // Snapshot pre-reload state
        Map<String, String> before = new LinkedHashMap<>();
        for (var entry : skillCommands.entrySet()) {
            before.put(entry.getKey().substring(1), entry.getValue().description());
        }

        Map<String, SkillCommandInfo> newCommands = scanSkillCommands();

        Map<String, String> after = new LinkedHashMap<>();
        for (var entry : newCommands.entrySet()) {
            after.put(entry.getKey().substring(1), entry.getValue().description());
        }

        return buildDiff(before, after, newCommands.size());
    }

    /**
     * S2: Reload diff result.
     */
    public record ReloadDiff(
        List<DiffEntry> added,
        List<DiffEntry> removed,
        List<String> unchanged,
        int total,
        int commands
    ) {}

    public record DiffEntry(String name, String description) {}

    static ReloadDiff buildDiff(Map<String, String> before, Map<String, String> after, int commandCount) {
        List<String> addedNames = new ArrayList<>(keySetDiff(after, before));
        Collections.sort(addedNames);
        List<String> removedNames = new ArrayList<>(keySetDiff(before, after));
        Collections.sort(removedNames);
        List<String> unchanged = new ArrayList<>(keySetIntersection(before, after));
        Collections.sort(unchanged);

        List<DiffEntry> added = addedNames.stream()
            .map(n -> new DiffEntry(n, after.get(n)))
            .toList();
        List<DiffEntry> removed = removedNames.stream()
            .map(n -> new DiffEntry(n, before.get(n)))
            .toList();

        return new ReloadDiff(added, removed, unchanged, after.size(), commandCount);
    }

    private static Set<String> keySetDiff(Map<String, String> a, Map<String, String> b) {
        Set<String> result = new LinkedHashSet<>(a.keySet());
        result.removeAll(b.keySet());
        return result;
    }

    private static Set<String> keySetIntersection(Map<String, String> a, Map<String, String> b) {
        Set<String> result = new LinkedHashSet<>(a.keySet());
        result.retainAll(b.keySet());
        return result;
    }

    /**
     * S2: Resolve current platform scope for disabled-skill filtering.
     */
    private String resolvePlatform() {
        String platform = System.getenv("HERMES_PLATFORM");
        if (platform != null && !platform.isBlank()) return platform;
        return null;
    }

    /**
     * S2: Bump usage tracking for a skill (on invocation).
     */
    public void bumpUse(String skillName) {
        // Delegate to SkillManager via bundleService which has a SkillManager
        try {
            bundleService.bumpUse(skillName);
        } catch (Exception e) {
            log.debug("Failed to bump usage for skill: {}", skillName);
        }
    }
}