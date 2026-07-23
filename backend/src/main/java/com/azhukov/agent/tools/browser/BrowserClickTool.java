package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_click",
    description = "Click an element on the current browser page.",
    toolset = "browser"
)
@Component
public class BrowserClickTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClickArgs args = ToolHandler.parseJson(arguments, ClickArgs.class);
        return ToolResult.ok("Browser click not yet implemented. Ref: " + args.ref());
    }

    public record ClickArgs(
        @ToolParam(description = "element reference id") String ref
    ) {}
}
