package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.ReviewToolProvider;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Implementation of ReviewToolProvider that delegates to concrete tool classes
 * in the tools.memory package. This breaks the core.memory ↔ tools.memory
 * circular dependency by putting the dependency direction one way:
 * tools.memory depends on core.memory (via the interface), not vice versa.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultReviewToolProvider implements ReviewToolProvider {

    private static final Set<String> REVIEW_TOOLS = Set.of(
        "memory", "skill_manage", "skills_list", "skill_view"
    );

    private final MemoryTool memoryTool;
    private final SkillManageTool skillManageTool;
    private final SkillsListTool skillsListTool;
    private final SkillViewTool skillViewTool;

    @Override
    public ToolResult execute(String toolName, String arguments, Session session) {
        return switch (toolName) {
            case "memory" -> memoryTool.execute(arguments, null, session);
            case "skill_manage" -> skillManageTool.execute(arguments, null, session);
            case "skills_list" -> skillsListTool.execute(arguments, null, session);
            case "skill_view" -> skillViewTool.execute(arguments, null, session);
            default -> ToolResult.fail("Unknown review tool: " + toolName);
        };
    }

    @Override
    public boolean isReviewTool(String toolName) {
        return REVIEW_TOOLS.contains(toolName);
    }
}