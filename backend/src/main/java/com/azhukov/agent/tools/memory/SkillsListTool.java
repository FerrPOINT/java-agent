package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S9: SkillsListTool — progressive disclosure with name, description, category.
 */
@AgentTool(
    name = "skills_list",
    description = "List available skills with name, category, and trust level.",
    toolset = "core"
)
@Component
@RequiredArgsConstructor
public class SkillsListTool implements ToolHandler {

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        // S9: Return name + category + trust level (not just name)
        var skills = skillManager.listSkills();
        if (skills.isEmpty()) {
            return ToolResult.ok("No skills available.");
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
}