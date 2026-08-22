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
import org.springframework.beans.factory.annotation.Autowired;
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
    description = "Create, update, delete, patch a skill, or manage support files (references/, templates/, scripts/). Actions: create, update (alias: edit), delete, patch, write_file, remove_file.",
    toolset = "skills")
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillManageTool implements ToolHandler {

    private final SkillManager skillManager;
    private final com.azhukov.agent.core.skill.SkillMutationLedger mutationLedger;

    /** Optional — cleared skills system-prompt cache after mutations (Hermes parity). */
    @Autowired(required = false)
    private transient com.azhukov.agent.core.prompt.PromptCacheTracker promptCacheTracker;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillManageArgs args = parseJson(arguments, SkillManageArgs.class);
        // S3: Get the effective write origin from WriteContext (FOREGROUND by default,
        // BACKGROUND_REVIEW during review)
        WriteOrigin origin = WriteContext.effectiveOrigin();
        try {
            ToolResult result = switch (args.action().toLowerCase()) {
                case "create" -> {
                    validateSkillName(args.name());
                    String content = generateFrontmatterIfNeeded(args.name(), args.content());
                    skillManager.saveSkill(args.name(), content, origin);
                    ledger("create", args.name(), null, content);
                    yield ToolResult.ok("Skill " + args.name() + " created.");
                }
                case "update", "edit" -> {
                    validateSkillName(args.name());
                    String before = snapshotSkill(args.name());
                    // Finding 4.4: Pass absorbed_into to saveSkill in update action
                    // "edit" is the Hermes name for the same action (full SKILL.md rewrite)
                    skillManager.saveSkill(args.name(), args.content(), origin, args.absorbed_into());
                    ledger("update", args.name(), before, args.content());
                    yield ToolResult.ok("Skill " + args.name() + " updated.");
                }
                case "delete" -> {
                    validateSkillName(args.name());
                    String before = snapshotSkill(args.name());
                    boolean deleted;
                    if (args.absorbed_into() != null && !args.absorbed_into().isBlank()) {
                        deleted = skillManager.deleteSkill(args.name(), args.absorbed_into());
                    } else {
                        deleted = skillManager.deleteSkill(args.name());
                    }
                    if (deleted) {
                        ledger("delete", args.name(), before, null);
                        yield ToolResult.ok("Skill " + args.name() + " deleted.");
                    }
                    yield ToolResult.fail("Skill " + args.name() + " not found.");
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
                        String before = snapshotSupportFile(args.name(), args.file_path());
                        boolean patched = skillManager.patchSupportFile(
                            args.name(), args.file_path(), args.old_text(), args.new_text(), replaceAll);
                        if (patched) {
                            ledger("patch", args.name(), before, snapshotSupportFile(args.name(), args.file_path()));
                            yield ToolResult.ok("File " + args.file_path() + " in skill " + args.name() + " patched.");
                        }
                        yield ToolResult.fail("Skill " + args.name() + " or file " + args.file_path() +
                            " not found, or old_text not found in file.");
                    } else {
                        // Patch SKILL.md
                        String before = snapshotSkill(args.name());
                        boolean patched = skillManager.patchSkill(
                            args.name(), args.old_text(), args.new_text(), replaceAll);
                        if (patched) {
                            ledger("patch", args.name(), before, snapshotSkill(args.name()));
                            yield ToolResult.ok("Skill " + args.name() + " patched.");
                        }
                        yield ToolResult.fail("Skill " + args.name() + " not found or old_text not found in content.");
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
                        String before = snapshotSupportFile(args.name(), args.file_path());
                        skillManager.writeSupportFile(args.name(), args.file_path(), args.content());
                        ledger("write_file", args.name(), before, args.content());
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
                    String before = snapshotSupportFile(args.name(), args.file_path());
                    boolean removed = skillManager.removeSupportFile(args.name(), args.file_path());
                    if (removed) {
                        ledger("remove_file", args.name(), before, null);
                        yield ToolResult.ok("File " + args.file_path() + " removed from skill " + args.name() + ".");
                    }
                    yield ToolResult.fail("File not found: " + args.file_path());
                }
                default -> ToolResult.fail("Unknown action: " + args.action());
            };
            // Hermes parity (skill_manager_tool.py:1654): every successful
            // skill mutation clears the cached skills system prompt so the
            // next turn's index reflects the change.
            if (result.success()) {
                afterSkillMutation(args.name());
            }
            return result;
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
     * h77 ledger hook (Hermes skill_ledger.record_mutation): telemetry, not a gate —
     * ledger failures are swallowed by SkillMutationLedger and never block the tool result.
     */
    private void ledger(String action, String skill, String oldValue, String newValue) {
        try {
            mutationLedger.record(action, skill, null, oldValue, newValue);
        } catch (Exception e) {
            log.debug("Skill ledger hook failed for '{}' ({}): {} — mutation unaffected", skill, action, e.getMessage());
        }
    }

    /**
     * Hermes parity (skill_manager_tool.py:1653-1657): after every successful
     * skill mutation — (a) clear the cached skills system prompt so the index
     * reflects the change on the next turn, (b) bump the skill's manage
     * counter (Hermes skill_usage.bump_patch / bump_use telemetry). Both are
     * best-effort and never block the mutation result.
     */
    private void afterSkillMutation(String skillName) {
        try {
            skillManager.incrementManageCount(skillName);
        } catch (Exception e) {
            log.debug("manage_count bump failed for '{}': {}", skillName, e.getMessage());
        }
        if (promptCacheTracker != null) {
            try {
                promptCacheTracker.invalidateAllSystemPrompts();
            } catch (Exception e) {
                log.debug("System prompt cache invalidation failed after skill mutation: {}", e.getMessage());
            }
        }
    }

    /** Best-effort pre-mutation snapshot of SKILL.md content (null when absent). */
    private String snapshotSkill(String name) {
        try {
            return skillManager.getSkill(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort pre-mutation snapshot of a support file (null when absent). */
    private String snapshotSupportFile(String name, String filePath) {
        try {
            var files = skillManager.listSupportFiles(name);
            if (files != null && files.contains(filePath)) {
                return skillManager.readSupportFile(name, filePath);
            }
            return null;
        } catch (Exception e) {
            return null;
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