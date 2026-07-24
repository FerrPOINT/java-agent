package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import org.springframework.stereotype.Component;

import static com.azhukov.agent.tools.ToolHandler.parseJson;

@AgentTool(name = "clarify", description = "Ask the user a clarifying question when the request is ambiguous or missing required information. Returns the question text.", toolset = "core")
@Component
public class ClarifyTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClarifyArgs args = parseJson(arguments, ClarifyArgs.class);
        return ToolResult.ok(args.question());
    }

    static class ClarifyArgs {
        @ToolParam(description = "The clarifying question to present to the user", required = true)
        private String question;

        public String question() { return question; }
    }
}
