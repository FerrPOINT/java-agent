package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_navigate",
    description = "Navigate browser to a URL and wait for load.",
    toolset = "browser"
)
@Component
public class BrowserNavigateTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        NavigateArgs args = ToolHandler.parseJson(arguments, NavigateArgs.class);
        return ToolResult.ok("Browser navigate not yet implemented. URL: " + args.url());
    }

    public record NavigateArgs(
        @ToolParam(description = "URL to navigate to") String url,
        @ToolParam(description = "wait for load timeout in seconds") int waitSeconds
    ) {}
}
