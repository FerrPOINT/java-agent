package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.SharedObjectMapper;
import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

/**
 * S3: Skill management tool — create, update, delete, patch, write_file, remove_file.
 * <p>
 * S3 fix: Uses {@link WriteContext} to determine the {@link WriteOrigin} for all
 * skill writes. When a review agent calls this tool, the origin is set to
 * {@code BACKGROUND_REVIEW} instead of {@code FOREGROUND}.
 */
@AgentTool(name = "skill_manage",
    description = "Manage skills (create, update, delete). Skills are your procedural "
        + "memory — reusable approaches for recurring task types. "
        + "New skills go to ~/.hermes/skills/; existing skills can be modified wherever they live.\n\n"
        + "Actions: create (full SKILL.md + optional category), "
        + "patch (old_string/new_string — preferred for fixes), "
        + "edit (full SKILL.md rewrite — major overhauls only), "
        + "delete, write_file, remove_file.\n\n"
        + "On delete, pass `absorbed_into=<umbrella>` when you're merging this "
        + "skill's content into another one, or `absorbed_into=\"\"` when you're "
        + "pruning it with no forwarding target. This lets the curator tell "
        + "consolidation from pruning without guessing, so downstream consumers "
        + "(cron jobs that reference the old skill name, etc.) get updated "
        + "correctly. The target you name in `absorbed_into` must already "
        + "exist — create/patch the umbrella first, then delete.\n\n"
        + "Create when: complex task succeeded (5+ calls), errors overcome, "
        + "user-corrected approach worked, non-trivial workflow discovered, "
        + "or user asks you to remember a procedure.\n"
        + "Update when: instructions stale/wrong, OS-specific failures, "
        + "missing steps or pitfalls found during use. "
        + "If you used a skill and hit issues not covered by it, patch it immediately.\n\n"
        + "After difficult/iterative tasks, offer to save as a skill. "
        + "Skip for simple one-offs. Confirm with user before creating/deleting.\n\n"
        + "Good skills: trigger conditions, numbered steps with exact commands, "
        + "pitfalls section, verification steps. Use skill_view() to see format examples.\n\n"
        + "Description: long descriptions are truncated to the first 57 chars "
        + "plus '...' in the system prompt skill index; longer text is visible "
        + "via skills_list/skill_view. Keep the trigger self-contained in that "
        + "first 57-char window: 'Use when <trigger>. <one-line behavior>.'\n\n"
        + "Pinned skills are protected from deletion only — skill_manage(action='delete') "
        + "will refuse with a message pointing the user to `hermes curator unpin <name>`. "
        + "Patches and edits go through on pinned skills so you can still improve them as "
        + "pitfalls come up; pin only guards against irrecoverable loss.",
    toolset = "skills")
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillManageTool implements ToolHandler {

    private static final ObjectMapper JSON = SharedObjectMapper.get();
    private static final int CHANGE_PREVIEW_CHARS = 200;

    private final SkillManager skillManager;
    private final com.azhukov.agent.core.skill.SkillMutationLedger mutationLedger;

    /** Optional — cleared skills system-prompt cache after mutations (Hermes parity). */
    @Autowired(required = false)
    private transient com.azhukov.agent.core.prompt.PromptCacheTracker promptCacheTracker;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillManageArgs args;
        try {
            args = parseJson(arguments == null || arguments.isBlank() ? "{}" : arguments, SkillManageArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }
        // S3: Get the effective write origin from WriteContext (FOREGROUND by default,
        // BACKGROUND_REVIEW during review)
        WriteOrigin origin = WriteContext.effectiveOrigin();
        try {
            String action = args.action() == null ? "" : args.action().toLowerCase();
            ToolResult result = switch (action) {
                case "create" -> {
                    validateSkillName(args.name());
                    if (args.content() == null || args.content().isBlank()) {
                        yield jsonError("content is required for 'create'. Provide the full SKILL.md text (frontmatter + body).");
                    }
                    String content = args.content();
                    skillManager.saveSkill(args.name(), content, origin);
                    ledger("create", args.name(), null, content);
                    Map<String, Object> payload = successPayload(
                        "create", args.name(), "Skill '" + args.name() + "' created.");
                    payload.put("path", args.name());
                    if (args.category() != null && !args.category().isBlank()) {
                        payload.put("category", args.category());
                    }
                    payload.put("_change", descriptionChange(content));
                    payload.put("hint", "To add reference files, templates, or scripts, use "
                        + "skill_manage(action='write_file', name='" + args.name()
                        + "', file_path='references/example.md', file_content='...')");
                    yield jsonOk(payload);
                }
                case "update", "edit" -> {
                    validateSkillName(args.name());
                    if (args.content() == null || args.content().isBlank()) {
                        yield jsonError("content is required for a full rewrite. Provide the full updated SKILL.md text.");
                    }
                    String before = snapshotSkill(args.name());
                    // Finding 4.4: Pass absorbed_into to saveSkill in update action
                    // "edit" is the Hermes name for the same action (full SKILL.md rewrite)
                    skillManager.saveSkill(args.name(), args.content(), origin, args.absorbed_into());
                    ledger("update", args.name(), before, args.content());
                    Map<String, Object> payload = successPayload(
                        "edit", args.name(), "Skill '" + args.name() + "' updated (full rewrite).");
                    payload.put("_change", descriptionChange(args.content()));
                    yield jsonOk(payload);
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
                        String message = "Skill '" + args.name() + "' deleted.";
                        if (args.absorbed_into() != null && !args.absorbed_into().isBlank()) {
                            message += " Content absorbed into '" + args.absorbed_into() + "'.";
                        }
                        Map<String, Object> payload = successPayload("delete", args.name(), message);
                        if (args.absorbed_into() != null) {
                            payload.put("absorbed_into", args.absorbed_into());
                        }
                        yield jsonOk(payload);
                    }
                    yield jsonError("Skill '" + args.name() + "' not found.");
                }
                case "patch" -> {
                    // S3: Find-and-replace text in skill content or support file
                    boolean hasContent = args.content() != null && !args.content().isBlank();
                    boolean hasOld = args.old_text() != null && !args.old_text().isBlank();
                    boolean hasNew = args.new_text() != null;
                    if (hasContent && (hasOld || hasNew)) {
                        yield jsonError("Pass EITHER content (full SKILL.md rewrite) OR old_string/new_string "
                            + "(targeted replacement), not both.");
                    }
                    if (hasContent) {
                        validateSkillName(args.name());
                        String before = snapshotSkill(args.name());
                        skillManager.saveSkill(args.name(), args.content(), origin, args.absorbed_into());
                        ledger("patch", args.name(), before, args.content());
                        Map<String, Object> payload = successPayload(
                            "patch", args.name(), "Skill '" + args.name() + "' updated (full rewrite).");
                        payload.put("_change", descriptionChange(args.content()));
                        yield jsonOk(payload);
                    }
                    if (!hasOld) {
                        yield jsonError("patch needs old_string/new_string for a targeted replacement, "
                            + "or content for a full SKILL.md rewrite (read it first with skill_view()).");
                    }
                    if (!hasNew) {
                        yield jsonError("new_string is required for 'patch'. Use empty string to delete matched text.");
                    }
                    boolean replaceAll = args.replace_all() != null && args.replace_all();
                    if (args.file_path() != null && !args.file_path().isBlank()) {
                        // Patch a support file (references/, templates/, scripts/)
                        String before = snapshotSupportFile(args.name(), args.file_path());
                        boolean patched;
                        try {
                            patched = skillManager.patchSupportFile(
                                args.name(), args.file_path(), args.old_text(), args.new_text(), replaceAll);
                        } catch (IllegalArgumentException e) {
                            yield jsonError(e.getMessage());
                        }
                        if (patched) {
                            ledger("patch", args.name(), before, snapshotSupportFile(args.name(), args.file_path()));
                            yield patchJson(args.name(), args.file_path(), args.old_text(), args.new_text(), replaceAll, before);
                        }
                        yield jsonError("Skill '" + args.name() + "' or file '" + args.file_path()
                            + "' not found, or old_string not found in file.");
                    } else {
                        // Patch SKILL.md
                        String before = snapshotSkill(args.name());
                        boolean patched;
                        try {
                            patched = skillManager.patchSkill(
                                args.name(), args.old_text(), args.new_text(), replaceAll);
                        } catch (IllegalArgumentException e) {
                            yield jsonError(e.getMessage());
                        }
                        if (patched) {
                            ledger("patch", args.name(), before, snapshotSkill(args.name()));
                            yield patchJson(args.name(), null, args.old_text(), args.new_text(), replaceAll, before);
                        }
                        yield jsonError("Skill '" + args.name() + "' not found or old_string not found in content.");
                    }
                }
                case "write_file" -> {
                    // S3: Write support file (references/, templates/, scripts/)
                    if (args.file_path() == null || args.file_path().isBlank()) {
                        yield jsonError("file_path is required for 'write_file'. Example: 'references/api-guide.md'");
                    }
                    if (args.content() == null) {
                        yield jsonError("file_content is required for 'write_file'.");
                    }
                    try {
                        String before = snapshotSupportFile(args.name(), args.file_path());
                        skillManager.writeSupportFile(args.name(), args.file_path(), args.content());
                        ledger("write_file", args.name(), before, args.content());
                        Map<String, Object> payload = successPayload(
                            "write_file", args.name(), "File '" + args.file_path() + "' written to skill '" + args.name() + "'.");
                        payload.put("file_path", args.file_path());
                        payload.put("path", args.file_path());
                        yield jsonOk(payload);
                    } catch (SecurityException e) {
                        // P2-49: Security scan failed — content was not written
                        yield jsonError("Security scan blocked: " + e.getMessage());
                    } catch (Exception e) {
                        yield jsonError("Failed to write file: " + e.getMessage());
                    }
                }
                case "remove_file" -> {
                    // S3: Remove support file
                    if (args.file_path() == null || args.file_path().isBlank()) {
                        yield jsonError("file_path is required for 'remove_file'.");
                    }
                    String before = snapshotSupportFile(args.name(), args.file_path());
                    boolean removed = skillManager.removeSupportFile(args.name(), args.file_path());
                    if (removed) {
                        ledger("remove_file", args.name(), before, null);
                        Map<String, Object> payload = successPayload(
                            "remove_file", args.name(), "File '" + args.file_path() + "' removed from skill '" + args.name() + "'.");
                        payload.put("file_path", args.file_path());
                        yield jsonOk(payload);
                    }
                    yield jsonError("File '" + args.file_path() + "' not found in skill '" + args.name() + "'.");
                }
                default -> jsonError("Unknown action '" + args.action()
                    + "'. Use: create, edit, patch, delete, write_file, remove_file");
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
            return jsonError("Security scan blocked: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return jsonError(e.getMessage());
        }
    }

    private ToolResult jsonOk(Map<String, Object> payload) {
        return ToolResult.ok(toJson(payload));
    }

    private ToolResult jsonError(String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", error);
        return new ToolResult(false, toJson(payload), error);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"Failed to serialize skill_manage result\"}";
        }
    }

    private Map<String, Object> successPayload(String action, String name, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("action", action);
        payload.put("name", name);
        payload.put("message", message);
        return payload;
    }

    private ToolResult patchJson(String name, String filePath, String oldText, String newText,
                                 boolean replaceAll, String before) {
        String target = filePath == null || filePath.isBlank() ? "SKILL.md" : filePath;
        Integer replacementCount = replacementCount(before, oldText, replaceAll);
        String countSuffix = replacementCount == null
            ? ""
            : " (" + replacementCount + " replacement" + (replacementCount == 1 ? "" : "s") + ")";
        Map<String, Object> payload = successPayload(
            "patch", name, "Patched " + target + " in skill '" + name + "'" + countSuffix + ".");
        if (filePath != null && !filePath.isBlank()) {
            payload.put("file_path", filePath);
        }
        payload.put("replace_all", replaceAll);
        if (replacementCount != null) {
            payload.put("replacements", replacementCount);
        }
        payload.put("_change", changePreview(oldText, newText));
        return jsonOk(payload);
    }

    private Integer replacementCount(String before, String oldText, boolean replaceAll) {
        if (before == null || oldText == null || oldText.isEmpty()) {
            return null;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = before.indexOf(oldText, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + oldText.length();
        }
        if (count == 0) {
            return null;
        }
        return replaceAll ? count : 1;
    }

    private Map<String, Object> changePreview(String oldText, String newText) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("old", preview(oldText));
        change.put("new", preview(newText));
        return change;
    }

    private Map<String, Object> descriptionChange(String content) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("description", preview(extractFrontmatterField(content, "description"), 120));
        return change;
    }

    private String extractFrontmatterField(String content, String field) {
        if (content == null || !content.startsWith("---")) {
            return "";
        }
        String prefix = field + ":";
        boolean inFrontmatter = false;
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if ("---".equals(trimmed)) {
                if (inFrontmatter) {
                    break;
                }
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter && trimmed.startsWith(prefix)) {
                return unquote(trimmed.substring(prefix.length()).trim());
            }
        }
        return "";
    }

    private String unquote(String value) {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String preview(String text) {
        return preview(text, CHANGE_PREVIEW_CHARS);
    }

    private String preview(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
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
     * S3: Validate skill names — lowercase, filesystem-safe, Hermes-compatible.
     */
    private void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        if (name.length() > 64) {
            throw new IllegalArgumentException("Skill name exceeds 64 characters.");
        }
        if (!name.matches("^[a-z0-9][a-z0-9._-]*$")) {
            throw new IllegalArgumentException(
                "Invalid skill name '" + name + "'. Use lowercase letters, numbers, " +
                "hyphens, dots, and underscores. Must start with a letter or digit.");
        }
    }

    record SkillManageArgs(
        @ToolParam(description = "Action: create, patch, delete, write_file, remove_file. Legacy update/edit aliases are accepted.", required = true)
        String action,
        @ToolParam(description = "Skill name (lowercase, hyphens, underscores, or dots)", required = true)
        String name,
        @ToolParam(description = "Full SKILL.md content (required for create; on patch performs full rewrite). For write_file, alias: file_content.", required = false)
        @com.fasterxml.jackson.annotation.JsonAlias("file_content") String content,
        @ToolParam(description = "Text to find and replace (for patch action). Alias: old_string.", required = false)
        @com.fasterxml.jackson.annotation.JsonProperty("old_text")
        @com.fasterxml.jackson.annotation.JsonAlias("old_string") String old_text,
        @ToolParam(description = "Replacement text (for patch action). Alias: new_string.", required = false)
        @com.fasterxml.jackson.annotation.JsonProperty("new_text")
        @com.fasterxml.jackson.annotation.JsonAlias("new_string") String new_text,
        @ToolParam(description = "File path under references/, templates/, or scripts/ (for write_file/remove_file/patch with file)", required = false)
        String file_path,
        @ToolParam(description = "Replace all occurrences (default false = first only) (for patch action)", required = false)
        Boolean replace_all,
        @ToolParam(description = "Skill name that absorbs this skill during deletion (for delete action, optional)", required = false)
        String absorbed_into,
        @ToolParam(description = "(Reserved, not yet implemented — ignored)", required = false)
        String category
    ) {}
}
