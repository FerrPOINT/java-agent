package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * S9: SkillsListTool — progressive disclosure with name, description, category.
 * <p>
 * P2-50: Optional category filter — when provided, only skills matching the
 * given category are returned (case-insensitive), matching Hermes behavior.
 */
@AgentTool(
    name = "skills_list",
    description = "List available skills with name, category, and trust level. "
        + "Optionally filter by category.",
    toolset = "core"
)
@Component
@RequiredArgsConstructor
public class SkillsListTool implements ToolHandler {

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillsListArgs args = ToolHandler.parseJson(arguments, SkillsListArgs.class);
        String categoryFilter = args.category();

        // S9: Return name + category + trust level (not just name)
        List<SkillManager.SkillInfo> skills = skillManager.listSkills();
        if (skills.isEmpty()) {
            return ToolResult.ok("No skills available.");
        }

        // P2-50: Filter by category when provided
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            skills = skills.stream()
                .filter(s -> s.category() != null
                    && s.category().equalsIgnoreCase(categoryFilter))
                .toList();
            if (skills.isEmpty()) {
                return ToolResult.ok("No skills available in category: " + categoryFilter);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Available Skills:\n");
        for (var skill : skills) {
            sb.append("  • ").append(skill.name());
            if (skill.category() != null && !skill.category().isBlank()) {
                sb.append(" [").append(skill.category()).append("]");
            }
            if (skill.trustLevel() != null) {
                sb.append(" (").append(skill.trustLevel()).append(")");
            }
            if (skill.archived()) {
                sb.append(" [ARCHIVED]");
            }
            sb.append("\n");
        }
        return ToolResult.ok(sb.toString().trim());
    }

    public record SkillsListArgs(
        @ToolParam(description = "Optional: filter skills by category (case-insensitive)", required = false)
        String category
    ) {}
}