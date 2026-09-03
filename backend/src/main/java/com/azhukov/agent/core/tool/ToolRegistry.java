package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.ToolHandler;
import java.util.List;
import java.util.Set;

public interface ToolRegistry {

    List<ToolDefinition> getDefinitions();

    List<ToolDefinition> getDefinitions(Set<String> toolsets);

    ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session);

    Set<String> getToolsets();

    void registerDynamic(String toolName, ToolDefinition definition, ToolHandler handler);

    default void registerDynamic(String toolName, String toolset, ToolDefinition definition, ToolHandler handler) {
        registerDynamic(toolName, definition, handler);
    }

    void deregisterDynamic(String toolName);
}
