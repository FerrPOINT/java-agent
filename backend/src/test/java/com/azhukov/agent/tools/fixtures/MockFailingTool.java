package com.azhukov.agent.tools.fixtures;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;

@AgentTool(name = "mock_failing", description = "Tool that always fails", toolset = "test")
public class MockFailingTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        throw new RuntimeException("mock failure: " + arguments);
    }
}
