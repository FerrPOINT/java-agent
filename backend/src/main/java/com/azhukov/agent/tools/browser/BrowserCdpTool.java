package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_cdp",
    description = "Send a raw Chrome DevTools Protocol command to the connected browser.",
    toolset = "browser"
)
@Component
public class BrowserCdpTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        CdpArgs args = ToolHandler.parseJson(arguments, CdpArgs.class);
        return ToolResult.ok("Browser CDP not yet implemented. Method: " + args.method());
    }

    public record CdpArgs(
        @ToolParam(description = "CDP method name") String method,
        @ToolParam(description = "CDP params JSON object") String params
    ) {}
}
