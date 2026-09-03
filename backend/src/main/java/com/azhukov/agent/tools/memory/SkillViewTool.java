package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.SharedObjectMapper;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
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
    description = "Skills allow for loading information about specific tasks and workflows, as well as scripts and templates. Load a skill's full content or access its linked files (references, templates, scripts). First call returns SKILL.md content plus a 'linked_files' dict showing available references/templates/scripts. To access those, call again with file_path parameter.",
    toolset = "skills"
)
@Component
@Slf4j
public class SkillViewTool implements ToolHandler {

    private static final ObjectMapper JSON = SharedObjectMapper.get();

    private final SkillManager skillManager;
    private SkillPreprocessor skillPreprocessor;
    private final AgentProperties agentProperties;

    public SkillViewTool(SkillManager skillManager) {
        this.skillManager = skillManager;
        this.agentProperties = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillViewTool(SkillManager skillManager, AgentProperties agentProperties,
                         SkillPreprocessor skillPreprocessor) {
        this.skillManager = skillManager;
        this.agentProperties = agentProperties;
        // Hermes parity (skills_tool.py:1013/1686): skill_view ALWAYS
        // preprocesses content (template vars + inline shell). The setter-only
        // wiring meant the preprocessor was never injected — preprocess=True
        // silently degraded to raw content.
        this.skillPreprocessor = skillPreprocessor;
    }

    @Autowired(required = false)
    public void setSkillPreprocessor(SkillPreprocessor skillPreprocessor) {
        this.skillPreprocessor = skillPreprocessor;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            SkillArgs args = ToolHandler.parseJson(arguments, SkillArgs.class);
            return executeParsed(args, session);
        } catch (Exception e) {
            log.error("SkillViewTool error — args: [{}], error: {}", arguments, e.toString(), e);
            return jsonFail("Skill view error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ToolResult executeParsed(SkillArgs args, Session session) {
        if (args.name() == null || args.name().isBlank()) {
            return jsonFail("name is required");
        }

        // S: If file_path is specified, read and return that specific support file
        if (args.file_path() != null && !args.file_path().isBlank()) {
            String fileContent = skillManager.readSupportFile(args.name(), args.file_path());
            if (fileContent == null) {
                return jsonFail("File not found: " + args.file_path() + " in skill " + args.name());
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
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("name", args.name());
            result.put("file", args.file_path());
            result.put("content", fileContent);
            result.put("file_type", fileType(args.file_path()));
            return jsonOk(result);
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
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", errMsg.toString());
            if (!lookupResult.collisionPaths().isEmpty()) {
                error.put("matches", lookupResult.collisionPaths());
            }
            return jsonFail(error);
        }

        SkillInfo info = lookupResult.info();
        if (info == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "Skill not found: " + args.name());
            error.put("hint", "Use skills_list to see all available skills");
            return jsonFail(error);
        }

        // Fix 3: Disabled skill check — refuse to view disabled skills
        if (info.disabled()) {
            return jsonFail("Skill '" + info.name() + "' is disabled. Enable it in config to use.");
        }

        // S7: Increment view count for telemetry
        skillManager.incrementViewCount(args.name());

        // S9: Build progressive disclosure output
        StringBuilder sb = new StringBuilder();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("name", info.name());

        // Metadata header
        sb.append("=== Skill: ").append(info.name()).append(" ===\n");
        result.put("description", descriptionOf(info));
        if (info.category() != null && !info.category().isBlank()) {
            sb.append("Category: ").append(info.category()).append("\n");
            result.put("category", info.category());
        }
        sb.append("Trust: ").append(info.trustLevel() != null ? info.trustLevel() : "AGENT_CREATED").append("\n");
        result.put("trust_level", info.trustLevel() != null ? info.trustLevel() : "AGENT_CREATED");
        sb.append("Views: ").append(info.viewCount()).append(" | Edits: ").append(info.manageCount()).append("\n");
        result.put("view_count", info.viewCount());
        result.put("manage_count", info.manageCount());
        if (info.updatedAt() != null) {
            sb.append("Updated: ").append(info.updatedAt()).append("\n");
            result.put("updated_at", info.updatedAt().toString());
        }
        if (info.archived()) {
            sb.append("Status: ARCHIVED\n");
            result.put("archived", true);
        }

        // Fix 4: Tags and related_skills
        if (info.tags() != null && !info.tags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", info.tags())).append("\n");
            result.put("tags", info.tags());
        }
        if (info.relatedSkills() != null && !info.relatedSkills().isEmpty()) {
            sb.append("Related: ").append(String.join(", ", info.relatedSkills())).append("\n");
            result.put("related_skills", info.relatedSkills());
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
            result.put("linked_files", linkedFilesToMap(linkedFiles));
            result.put("usage_hint", "To view linked files, call skill_view(name, file_path) where file_path is e.g. 'references/api.md' or 'assets/config.yaml'");
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
                result.put("required_environment_variables", requiredEnvVars);
                result.put("missing_required_environment_variables", missing);
                result.put("setup_needed", !missing.isEmpty());
                result.put("readiness_status", missing.isEmpty() ? "available" : "setup_needed");
                sb.append("\n--- Environment Setup ---\n");
                sb.append("Readiness: ").append(readinessStatus).append("\n");

                if (!missing.isEmpty()) {
                    sb.append("Setup needed: true\n");
                    sb.append("Missing env vars: ").append(String.join(", ", missing)).append("\n");
                    sb.append("Setup help: Set the following env vars: ");
                    StringBuilder setupHelp = new StringBuilder("Set the following env vars: ");
                    for (int i = 0; i < missing.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(missing.get(i));
                        if (i > 0) setupHelp.append(", ");
                        setupHelp.append(missing.get(i));
                    }
                    sb.append("\n");
                    result.put("setup_help", setupHelp.toString());

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

        result.put("content", sb.toString());
        return jsonOk(result);
    }

    public record SkillArgs(
        @ToolParam(description = "skill name") String name,
        @ToolParam(description = "Optional: specific support file path (references/, templates/, scripts/) to read instead of SKILL.md", required = false) String file_path
    ) {}

    /**
     * Resolve the filesystem directory for a skill (for ${HERMES_SKILL_DIR} substitution).
     * Returns null if skills are DB-only with no filesystem representation.
     * Finding 4.3: Uses the configured working directory instead of hardcoded "skills" path.
     */
    private String resolveSkillDir(String skillName) {
        try {
            // Finding 4.3: Use the configured working directory from AgentProperties
            String workingDir = System.getProperty("user.dir");
            if (agentProperties != null && agentProperties.getCore() != null
                && agentProperties.getCore().getWorkingDirectory() != null
                && !agentProperties.getCore().getWorkingDirectory().isBlank()) {
                workingDir = agentProperties.getCore().getWorkingDirectory();
            }
            java.nio.file.Path candidate = java.nio.file.Path.of(workingDir, "skills", skillName);
            if (java.nio.file.Files.isDirectory(candidate)) {
                return candidate.toString();
            }
        } catch (Exception e) {
            log.debug("Could not resolve skill dir for '{}': {}", skillName, e.getMessage());
        }
        return null;
    }

    private static String descriptionOf(SkillInfo info) {
        String description = info.description();
        if (description != null && !description.isBlank()) {
            return description;
        }
        if (info.content() != null && info.content().startsWith("---")) {
            SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(info.content());
            Object frontmatterDescription = fr.frontmatter().get("description");
            if (frontmatterDescription != null) {
                return String.valueOf(frontmatterDescription);
            }
        }
        return "";
    }

    private static Map<String, Object> linkedFilesToMap(LinkedFiles linkedFiles) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!linkedFiles.references().isEmpty()) {
            result.put("references", linkedFiles.references());
        }
        if (!linkedFiles.templates().isEmpty()) {
            result.put("templates", linkedFiles.templates());
        }
        if (!linkedFiles.assets().isEmpty()) {
            result.put("assets", linkedFiles.assets());
        }
        if (!linkedFiles.scripts().isEmpty()) {
            result.put("scripts", linkedFiles.scripts());
        }
        return result;
    }

    private static String fileType(String filePath) {
        if (filePath == null) {
            return "";
        }
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String filename = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private static ToolResult jsonOk(Map<String, Object> result) {
        try {
            return ToolResult.ok(JSON.writeValueAsString(result));
        } catch (IOException e) {
            return ToolResult.ok(String.valueOf(result));
        }
    }

    private static ToolResult jsonFail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        return jsonFail(result);
    }

    private static ToolResult jsonFail(Map<String, Object> result) {
        try {
            return new ToolResult(false, JSON.writeValueAsString(result), errorFrom(result));
        } catch (IOException e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Skill view failed\"}", "Skill view failed");
        }
    }

    private static String errorFrom(Map<String, Object> result) {
        Object error = result.get("error");
        if (error instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
