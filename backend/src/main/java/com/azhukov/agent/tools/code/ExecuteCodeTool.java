package com.azhukov.agent.tools.code;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "execute_code",
    description = "Execute a Python code snippet in the project CWD and return stdout/stderr.",
    toolset = "coding"
)
@Component
public class ExecuteCodeTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ExecuteCodeArgs args = ToolHandler.parseJson(arguments, ExecuteCodeArgs.class);
        return ToolResult.ok("execute_code is not yet implemented. Snippet length: " + (args.code() != null ? args.code().length() : 0));
    }

    public record ExecuteCodeArgs(
        @ToolParam(description = "Python code to execute") String code,
        @ToolParam(description = "timeout in seconds") int timeout
    ) {}
}
