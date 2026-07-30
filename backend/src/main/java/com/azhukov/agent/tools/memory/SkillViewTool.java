package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S9: SkillViewTool — progressive disclosure with metadata, linked files, frontmatter.
 */
@AgentTool(
    name = "skill_view",
    description = "Read a skill by name. Returns content with metadata, YAML frontmatter, and linked support files.",
    toolset = "core"
)
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillViewTool implements ToolHandler {

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillArgs args = ToolHandler.parseJson(arguments, SkillArgs.class);

        // S9: Get rich skill info (with metadata)
        var info = skillManager.getSkillInfo(args.name());
        if (info == null) {
            return ToolResult.fail("Skill not found: " + args.name());
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
        sb.append("\n");

        // Content
        sb.append(info.content());

        // S9: Detect and list support files
        List<String> supportFiles = skillManager.listSupportFiles(args.name());
        if (supportFiles != null && !supportFiles.isEmpty()) {
            sb.append("\n\n--- Linked Files ---\n");
            for (String file : supportFiles) {
                sb.append("  - ").append(file).append("\n");
            }
        }

        return ToolResult.ok(sb.toString());
    }

    public record SkillArgs(
        @ToolParam(description = "skill name") String name
    ) {}
}