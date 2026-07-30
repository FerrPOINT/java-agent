package com.azhukov.agent.tools.memory;

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
        return switch (args.action().toLowerCase()) {
            case "create" -> {
                validateSkillName(args.name());
                String content = generateFrontmatterIfNeeded(args.name(), args.content());
                skillManager.saveSkill(args.name(), content, WriteOrigin.FOREGROUND);
                yield ToolResult.ok("Skill " + args.name() + " created.");
            }
            case "update" -> {
                validateSkillName(args.name());
                skillManager.saveSkill(args.name(), args.content(), WriteOrigin.FOREGROUND);
                yield ToolResult.ok("Skill " + args.name() + " updated.");
            }
            case "delete" -> {
                validateSkillName(args.name());
                boolean deleted = skillManager.deleteSkill(args.name());
                yield deleted
                    ? ToolResult.ok("Skill " + args.name() + " deleted.")
                    : ToolResult.fail("Skill " + args.name() + " not found.");
            }
            case "patch" -> {
                // S3: Find-and-replace text in skill content
                if (args.old_text() == null || args.old_text().isBlank()) {
                    yield ToolResult.fail("old_text is required for patch action");
                }
                if (args.new_text() == null) {
                    yield ToolResult.fail("new_text is required for patch action");
                }
                boolean patched = skillManager.patchSkill(args.name(), args.old_text(), args.new_text());
                yield patched
                    ? ToolResult.ok("Skill " + args.name() + " patched.")
                    : ToolResult.fail("Skill " + args.name() + " not found or old_text not found in content.");
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

    static class SkillManageArgs {
        @ToolParam(description = "Action: create, update, delete, patch, write_file, remove_file", required = true)
        private String action;
        @ToolParam(description = "Skill name (lowercase, hyphens)", required = true)
        private String name;
        @ToolParam(description = "Skill markdown content (required for create/update/patch)", required = false)
        private String content;
        @ToolParam(description = "Text to find and replace (for patch action)", required = false)
        private String old_text;
        @ToolParam(description = "Replacement text (for patch action)", required = false)
        private String new_text;
        @ToolParam(description = "File path under references/, templates/, or scripts/ (for write_file/remove_file)", required = false)
        private String file_path;

        public String action() { return action; }
        public String name() { return name; }
        public String content() { return content; }
        public String old_text() { return old_text; }
        public String new_text() { return new_text; }
        public String file_path() { return file_path; }
    }
}