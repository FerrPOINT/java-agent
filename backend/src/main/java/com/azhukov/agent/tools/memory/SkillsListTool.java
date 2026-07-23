package com.azhukov.agent.tools.memory;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "skills_list",
    description = "List available skills.",
    toolset = "core"
)
@Component
public class SkillsListTool implements ToolHandler {

    private final SkillManager skillManager;

    public SkillsListTool(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        return ToolResult.ok(String.join("\n", skillManager.listSkillNames()));
    }
}
