package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillManager.LinkedFiles;
import com.azhukov.agent.core.skill.SkillManager.SkillInfo;
import com.azhukov.agent.core.skill.SkillManager.SkillLookupResult;
import com.azhukov.agent.core.skill.SkillPreprocessor;
import com.azhukov.agent.core.skill.SkillSecurityScanner;
import com.azhukov.agent.core.skill.SkillUtils;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * S9: SkillViewTool — progressive disclosure with metadata, linked files, frontmatter.
 * <p>
 * Implements (porting from Hermes skills_tool.py):
 * <ul>
 *   <li>Fix 1: Required environment variable checks — parses {@code required_environment_variables}
 *       from frontmatter, checks which env vars are NOT set in {@code System.getenv()},
 *       adds {@code setup_needed}, {@code missing_required_environment_variables},
 *       {@code readiness_status}, and {@code setup_help} to the response.</li>
 *   <li>Fix 2: Injection pattern detection — scans skill content for suspicious patterns
 *       (same patterns as {@link SkillSecurityScanner} uses on save). Does NOT block viewing,
 *       just adds a warning.</li>
 *   <li>Fix 3: Disabled skill check — checks if skill has {@code disabled: true} in frontmatter
 *       or DB. If disabled, returns an error.</li>
 *   <li>Fix 4: Tags and related_skills extraction — parses {@code tags} and {@code related_skills}
 *       from frontmatter (comma-separated or YAML list), includes both in response.</li>
 *   <li>Fix 5: Linked files organized by type — organizes support files into
 *       {@code references/}, {@code templates/}, {@code scripts/}, {@code assets/}.</li>
 *   <li>Fix 6: Multi-strategy lookup — uses {@link SkillManager#getSkillInfoMultiStrategy(String)}
 *       which tries: direct DB lookup, recursive filesystem search by directory name,
 *       frontmatter {@code name:} field match, and legacy flat {@code <name>.md}.</li>
 * </ul>
 * <p>
 * P0-8: Applies SkillPreprocessor (template vars + inline shell) to skill content
 * before returning, matching Hermes skill_view(preprocess=True) behavior.
 */
@AgentTool(
    name = "skill_view",
    description = "Read a skill by name. Returns content with metadata, YAML frontmatter, and linked support files.",
    toolset = "core"
)
@Component
@Slf4j
public class SkillViewTool implements ToolHandler {

    private final SkillManager skillManager;
    private SkillPreprocessor skillPreprocessor;

    public SkillViewTool(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Autowired(required = false)
    public void setSkillPreprocessor(SkillPreprocessor skillPreprocessor) {
        this.skillPreprocessor = skillPreprocessor;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillArgs args = ToolHandler.parseJson(arguments, SkillArgs.class);

        // S: If file_path is specified, read and return that specific support file
        if (args.file_path() != null && !args.file_path().isBlank()) {
            String fileContent = skillManager.readSupportFile(args.name(), args.file_path());
            if (fileContent == null) {
                return ToolResult.fail("File not found: " + args.file_path() + " in skill " + args.name());
            }
            // P0-8: Apply preprocessing to support files too (template vars only, no inline shell)
            if (skillPreprocessor != null && skillPreprocessor.isEnabled()) {
                String sessionId = session != null && session.id() != null ? session.id().toString() : null;
                String skillDir = resolveSkillDir(args.name());
                try {
                    fileContent = skillPreprocessor.preprocess(fileContent, sessionId, skillDir);
                } catch (Exception e) {
                    log.warn("Failed to preprocess support file '{}': {}", args.file_path(), e.getMessage());
                }
            }
            return ToolResult.ok(fileContent);
        }

        // Fix 6: Multi-strategy lookup — DB, filesystem by dir name, frontmatter name match, legacy flat .md
        SkillLookupResult lookupResult = skillManager.getSkillInfoMultiStrategy(args.name());

        // Collision detection — multiple skills matched
        if (lookupResult.error() != null) {
            StringBuilder errMsg = new StringBuilder(lookupResult.error());
            if (!lookupResult.collisionPaths().isEmpty()) {
                errMsg.append("\nMatches: ");
                errMsg.append(String.join(", ", lookupResult.collisionPaths()));
            }
            return ToolResult.fail(errMsg.toString());
        }

        SkillInfo info = lookupResult.info();
        if (info == null) {
            return ToolResult.fail("Skill not found: " + args.name());
        }

        // Fix 3: Disabled skill check — refuse to view disabled skills
        if (info.disabled()) {
            return ToolResult.fail("Skill '" + info.name() + "' is disabled. Enable it in config to use.");
        }

        // S7: Increment view count for telemetry
        skillManager.incrementViewCount(args.name());

        // S9: Build progressive disclosure output
        StringBuilder sb = new StringBuilder();

        // Metadata header
        sb.append("=== Skill: ").append(info.name()).append(" ===\n");
        if (info.category() != null && !info.category().isBlank()) {
            sb.append("Category: ").append(info.category()).append("\n");
        }
        sb.append("Trust: ").append(info.trustLevel() != null ? info.trustLevel() : "AGENT_CREATED").append("\n");
        sb.append("Views: ").append(info.viewCount()).append(" | Edits: ").append(info.manageCount()).append("\n");
        if (info.updatedAt() != null) {
            sb.append("Updated: ").append(info.updatedAt()).append("\n");
        }
        if (info.archived()) {
            sb.append("Status: ARCHIVED\n");
        }

        // Fix 4: Tags and related_skills
        if (info.tags() != null && !info.tags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", info.tags())).append("\n");
        }
        if (info.relatedSkills() != null && !info.relatedSkills().isEmpty()) {
            sb.append("Related: ").append(String.join(", ", info.relatedSkills())).append("\n");
        }

        sb.append("\n");

        // Content — apply preprocessing (template vars + inline shell) if available
        String content = info.content();
        if (skillPreprocessor != null && skillPreprocessor.isEnabled()) {
            String sessionId = session != null && session.id() != null ? session.id().toString() : null;
            String skillDir = resolveSkillDir(args.name());
            try {
                content = skillPreprocessor.preprocess(content, sessionId, skillDir);
            } catch (Exception e) {
                log.warn("Failed to preprocess skill '{}': {}", args.name(), e.getMessage());
                // Fall back to raw content on preprocessing failure
            }
        }

        // Fix 2: Injection pattern detection — scan for suspicious patterns, warn but don't block
        List<String> injectionMatches = SkillUtils.detectInjectionPatterns(content);
        if (!injectionMatches.isEmpty()) {
            sb.append("⚠️ WARNING: Skill content contains patterns that may indicate prompt injection:\n");
            for (String pattern : injectionMatches) {
                sb.append("  - \"").append(pattern).append("\"\n");
            }
            sb.append("\n");
            log.warn("Skill security warning for '{}': injection patterns detected: {}", args.name(), injectionMatches);
        }

        sb.append(content);

        // Fix 5: Linked files organized by type (references, templates, scripts, assets)
        LinkedFiles linkedFiles = info.linkedFiles();
        if (linkedFiles == null) {
            // Fallback: use listSupportFilesByType from the manager
            linkedFiles = skillManager.listSupportFilesByType(args.name());
        }

        if (linkedFiles != null && !linkedFiles.isEmpty()) {
            sb.append("\n\n--- Linked Files ---\n");
            if (!linkedFiles.references().isEmpty()) {
                sb.append("  References:\n");
                for (String file : linkedFiles.references()) {
                    sb.append("    - ").append(file).append("\n");
                }
            }
            if (!linkedFiles.templates().isEmpty()) {
                sb.append("  Templates:\n");
                for (String file : linkedFiles.templates()) {
                    sb.append("    - ").append(file).append("\n");
                }
            }
            if (!linkedFiles.scripts().isEmpty()) {
                sb.append("  Scripts:\n");
                for (String file : linkedFiles.scripts()) {
                    sb.append("    - ").append(file).append("\n");
                }
            }
            if (!linkedFiles.assets().isEmpty()) {
                sb.append("  Assets:\n");
                for (String file : linkedFiles.assets()) {
                    sb.append("    - ").append(file).append("\n");
                }
            }
            sb.append("\n  Usage: To view linked files, call skill_view(name, file_path) where file_path is e.g. 'references/api.md' or 'assets/config.yaml'\n");
        }

        // Fix 1: Required environment variable checks
        if (content != null && content.startsWith("---")) {
            SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
            Map<String, Object> frontmatter = fr.frontmatter();
            List<Map<String, Object>> requiredEnvVars =
                SkillUtils.extractRequiredEnvironmentVariables(frontmatter);

            if (!requiredEnvVars.isEmpty()) {
                List<String> missing = SkillUtils.findMissingEnvironmentVariables(requiredEnvVars);
                String readinessStatus = missing.isEmpty() ? "ready" : "incomplete";
                sb.append("\n--- Environment Setup ---\n");
                sb.append("Readiness: ").append(readinessStatus).append("\n");

                if (!missing.isEmpty()) {
                    sb.append("Setup needed: true\n");
                    sb.append("Missing env vars: ").append(String.join(", ", missing)).append("\n");
                    sb.append("Setup help: Set the following env vars: ");
                    for (int i = 0; i < missing.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(missing.get(i));
                    }
                    sb.append("\n");

                    // Include help text if available
                    for (Map<String, Object> entry : requiredEnvVars) {
                        if (entry.get("help") != null && missing.contains(String.valueOf(entry.get("name")))) {
                            sb.append("  Help for ").append(entry.get("name")).append(": ")
                                .append(entry.get("help")).append("\n");
                        }
                    }
                } else {
                    sb.append("All required env vars are set.\n");
                }
            }
        }

        return ToolResult.ok(sb.toString());
    }

    public record SkillArgs(
        @ToolParam(description = "skill name") String name,
        @ToolParam(description = "Optional: specific support file path (references/, templates/, scripts/) to read instead of SKILL.md", required = false) String file_path
    ) {}

    /**
     * Resolve the filesystem directory for a skill (for ${HERMES_SKILL_DIR} substitution).
     * Returns null if skills are DB-only with no filesystem representation.
     */
    private String resolveSkillDir(String skillName) {
        try {
            // Attempt to find the skill directory on disk
            String workingDir = System.getProperty("user.dir");
            java.nio.file.Path candidate = java.nio.file.Path.of(workingDir, "skills", skillName);
            if (java.nio.file.Files.isDirectory(candidate)) {
                return candidate.toString();
            }
        } catch (Exception e) {
            log.debug("Could not resolve skill dir for '{}': {}", skillName, e.getMessage());
        }
        return null;
    }
}