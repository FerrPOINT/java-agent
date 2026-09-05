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

    /**
     * Expand toolset names (static specs + composites) into the concrete tool
     * names they grant. Unknown names are skipped. Needed by delegation to
     * subtract blocked TOOL names after composite expansion (Hermes parity —
     * {@code model_tools} subtracts blocked names post-expansion, so mixed
     * bundles like hermes-cli keep their allowed tools while blocked ones are
     * removed; stripping whole toolsets leaks blocked tools through composites).
     */
    default Set<String> expandToolsetNames(Set<String> toolsets) {
        return Set.of();
    }

    void registerDynamic(String toolName, ToolDefinition definition, ToolHandler handler);

    default void registerDynamic(String toolName, String toolset, ToolDefinition definition, ToolHandler handler) {
        registerDynamic(toolName, definition, handler);
    }

    void deregisterDynamic(String toolName);
}
