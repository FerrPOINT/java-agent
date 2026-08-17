package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;

import java.util.Map;

/**
 * Abstraction for executing review-whitelisted tools without coupling
 * core.memory to the tools.memory package (breaks the circular dependency
 * core.memory ↔ tools.memory).
 * <p>
 * Implementations live in the tools layer and delegate to concrete tool classes.
 */
public interface ReviewToolProvider {

    /**
     * Execute a review tool by name.
     *
     * @param toolName one of: memory, skill_manage, skills_list, skill_view
     * @param arguments the tool call arguments as a JSON string
     * @param session the review session
     * @return the tool result
     */
    ToolResult execute(String toolName, String arguments, Session session);

    /**
     * Check if a tool name is a review-whitelisted tool.
     */
    boolean isReviewTool(String toolName);
}