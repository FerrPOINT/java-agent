package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

/**
 * S3: Skill management tool — create, update, delete, patch, write_file, remove_file.
 * <p>
 * S3 fix: Uses {@link WriteContext} to determine the {@link WriteOrigin} for all
 * skill writes. When a review agent calls this tool, the origin is set to
 * {@code BACKGROUND_REVIEW} instead of {@code FOREGROUND}.
 */
@AgentTool(name = "skill_manage",
    description = "Create, update, delete, patch a skill, or manage support files (references/, templates/, scripts/). Actions: create, update, delete, patch, write_file, remove_file.",
    toolset = "skills")
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillManageTool implements ToolHandler {

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillManageArgs args = parseJson(arguments, SkillManageArgs.class);
        // S3: Get the effective write origin from WriteContext (FOREGROUND by default,
        // BACKGROUND_REVIEW during review)
        WriteOrigin origin = WriteContext.effectiveOrigin();
        try {
            return switch (args.action().toLowerCase()) {
                case "create" -> {
                    validateSkillName(args.name());
                    String content = generateFrontmatterIfNeeded(args.name(), args.content());
                    skillManager.saveSkill(args.name(), content, origin);
                    yield ToolResult.ok("Skill " + args.name() + " created.");
                }
                case "update" -> {
                    validateSkillName(args.name());
                    // Finding 4.4: Pass absorbed_into to saveSkill in update action
                    skillManager.saveSkill(args.name(), args.content(), origin, args.absorbed_into());
                    yield ToolResult.ok("Skill " + args.name() + " updated.");
                }
                case "delete" -> {
                    validateSkillName(args.name());
                    boolean deleted;
                    if (args.absorbed_into() != null && !args.absorbed_into().isBlank()) {
                        deleted = skillManager.deleteSkill(args.name(), args.absorbed_into());
                    } else {
                        deleted = skillManager.deleteSkill(args.name());
                    }
                    yield deleted
                        ? ToolResult.ok("Skill " + args.name() + " deleted.")
                        : ToolResult.fail("Skill " + args.name() + " not found.");
                }
                case "patch" -> {
                    // S3: Find-and-replace text in skill content or support file
                    if (args.old_text() == null || args.old_text().isBlank()) {
                        yield ToolResult.fail("old_text is required for patch action");
                    }
                    if (args.new_text() == null) {
                        yield ToolResult.fail("new_text is required for patch action");
                    }
                    boolean replaceAll = args.replace_all() != null && args.replace_all();
                    if (args.file_path() != null && !args.file_path().isBlank()) {
                        // Patch a support file (references/, templates/, scripts/)
                        boolean patched = skillManager.patchSupportFile(
                            args.name(), args.file_path(), args.old_text(), args.new_text(), replaceAll);
                        yield patched
                            ? ToolResult.ok("File " + args.file_path() + " in skill " + args.name() + " patched.")
                            : ToolResult.fail("Skill " + args.name() + " or file " + args.file_path() +
                                " not found, or old_text not found in file.");
                    } else {
                        // Patch SKILL.md
                        boolean patched = skillManager.patchSkill(
                            args.name(), args.old_text(), args.new_text(), replaceAll);
                        yield patched
                            ? ToolResult.ok("Skill " + args.name() + " patched.")
                            : ToolResult.fail("Skill " + args.name() + " not found or old_text not found in content.");
                    }
                }
                case "write_file" -> {
                    // S3: Write support file (references/, templates/, scripts/)
                    if (args.file_path() == null || args.file_path().isBlank()) {
                        yield ToolResult.fail("file_path is required for write_file action");
                    }
                    if (args.content() == null) {
                        yield ToolResult.fail("content is required for write_file action");
                    }
                    try {
                        skillManager.writeSupportFile(args.name(), args.file_path(), args.content());
                        yield ToolResult.ok("File " + args.file_path() + " written to skill " + args.name() + ".");
                    } catch (SecurityException e) {
                        // P2-49: Security scan failed — content was not written
                        yield ToolResult.fail("Security scan blocked: " + e.getMessage());
                    } catch (Exception e) {
                        yield ToolResult.fail("Failed to write file: " + e.getMessage());
                    }
                }
                case "remove_file" -> {
                    // S3: Remove support file
                    if (args.file_path() == null || args.file_path().isBlank()) {
                        yield ToolResult.fail("file_path is required for remove_file action");
                    }
                    boolean removed = skillManager.removeSupportFile(args.name(), args.file_path());
                    yield removed
                        ? ToolResult.ok("File " + args.file_path() + " removed from skill " + args.name() + ".")
                        : ToolResult.fail("File not found: " + args.file_path());
                }
                default -> ToolResult.fail("Unknown action: " + args.action());
            };
        } catch (SecurityException e) {
            // P2-49: Security scan failed — content was not persisted. The scan runs
            // BEFORE the write in saveSkill/writeSupportFile (scanAndGuard → throw
            // before DB write), so the original content is untouched. No explicit
            // rollback needed — this matches Hermes behavior where the scan gate
            // prevents the write rather than reverting it after the fact.
            log.warn("Security scan blocked skill edit '{}': {} — original content preserved", args.name(), e.getMessage());
            return ToolResult.fail("Security scan blocked: " + e.getMessage());
        }
    }

    /**
     * S3: Validate skill names — lowercase, hyphens, no spaces.
     */
    private void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        if (!name.matches("^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]$")) {
            throw new IllegalArgumentException(
                "Skill name must be lowercase, use hyphens (not spaces/underscores), " +
                "start and end with alphanumeric: " + name);
        }
        if (name.contains("--")) {
            throw new IllegalArgumentException("Skill name must not contain consecutive hyphens: " + name);
        }
    }

    /**
     * S3: Generate basic YAML frontmatter on create if not already present.
     */
    private String generateFrontmatterIfNeeded(String name, String content) {
        if (content == null || content.isBlank()) {
            content = "# " + name.replace("-", " ") + "\n\n";
        }
        if (content.startsWith("---")) {
            // Already has frontmatter
            return content;
        }
        String frontmatter = "---\n" +
            "name: " + name + "\n" +
            "category: \"\"\n" +
            "description: \"\"\n" +
            "---\n\n";
        return frontmatter + content;
    }

    record SkillManageArgs(
        @ToolParam(description = "Action: create, update, delete, patch, write_file, remove_file", required = true)
        String action,
        @ToolParam(description = "Skill name (lowercase, hyphens)", required = true)
        String name,
        @ToolParam(description = "Skill markdown content (required for create/update/patch)", required = false)
        String content,
        @ToolParam(description = "Text to find and replace (for patch action)", required = false)
        String old_text,
        @ToolParam(description = "Replacement text (for patch action)", required = false)
        String new_text,
        @ToolParam(description = "File path under references/, templates/, or scripts/ (for write_file/remove_file/patch with file)", required = false)
        String file_path,
        @ToolParam(description = "Replace all occurrences (default false = first only) (for patch action)", required = false)
        Boolean replace_all,
        @ToolParam(description = "Skill name that absorbs this skill during deletion (for delete action, optional)", required = false)
        String absorbed_into
    ) {}
}