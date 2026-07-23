package com.azhukov.agent.tools.memory;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "skill_view",
    description = "Read a skill by name.",
    toolset = "core"
)
@Component
public class SkillViewTool implements ToolHandler {

    private final SkillManager skillManager;

    public SkillViewTool(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillArgs args = ToolHandler.parseJson(arguments, SkillArgs.class);
        String content = skillManager.getSkill(args.name());
        return content != null ? ToolResult.ok(content) : ToolResult.fail("Skill not found: " + args.name());
    }

    public record SkillArgs(
        @ToolParam(description = "skill name") String name
    ) {}
}
