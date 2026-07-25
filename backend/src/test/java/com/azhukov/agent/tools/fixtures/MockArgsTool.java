package com.azhukov.agent.tools.fixtures;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;

@AgentTool(name = "mock_args", description = "Tool with args record", toolset = "test")
public class MockArgsTool implements ToolHandler {

    public record Args(@ToolParam(description = "input value") String value,
                       @ToolParam(description = "count", required = false) int count,
                       @ToolParam(description = "flag", required = false) boolean flag) {}

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        Args args = ToolHandler.parseJson(arguments, Args.class);
        return ToolResult.ok(args.value + " " + args.count + " " + args.flag);
    }
}
