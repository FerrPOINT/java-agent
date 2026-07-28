package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

@AgentTool(name = "skill_manage", description = "Create, update, or delete a skill document. Actions: create, update, delete.", toolset = "skills")
@Component
@RequiredArgsConstructor
public class SkillManageTool implements ToolHandler {

    private final SkillManager skillManager;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SkillManageArgs args = parseJson(arguments, SkillManageArgs.class);
        return switch (args.action().toLowerCase()) {
            case "create", "update" -> {
                skillManager.saveSkill(args.name(), args.content());
                yield ToolResult.ok("Skill " + args.name() + " saved.");
            }
            case "delete" -> {
                boolean deleted = skillManager.deleteSkill(args.name());
                yield deleted
                    ? ToolResult.ok("Skill " + args.name() + " deleted.")
                    : ToolResult.fail("Skill " + args.name() + " not found.");
            }
            default -> ToolResult.fail("Unknown action: " + args.action());
        };
    }

    static class SkillManageArgs {
        @ToolParam(description = "Action: create, update, or delete", required = true)
        private String action;
        @ToolParam(description = "Skill name", required = true)
        private String name;
        @ToolParam(description = "Skill markdown content (required for create/update)", required = false)
        private String content;

        public String action() { return action; }
        public String name() { return name; }
        public String content() { return content; }
    }
}
