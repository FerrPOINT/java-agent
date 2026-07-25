package com.azhukov.agent.tools.fixtures;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;

@AgentTool(name = "mock_echo", description = "Echo tool for tests", toolset = "test")
public class MockEchoTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        return ToolResult.ok(arguments);
    }
}
