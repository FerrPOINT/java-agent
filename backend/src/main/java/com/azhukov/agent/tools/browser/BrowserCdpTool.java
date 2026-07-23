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
    description = "Send a raw Chrome DevTools Protocol command and return the result.",
    toolset = "browser"
)
@Component
public class BrowserCdpTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserCdpTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        CdpArgs args = ToolHandler.parseJson(arguments, CdpArgs.class);
        try {
            String result = browserService.evaluate(args.expression());
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.fail("Browser CDP evaluate failed: " + e.getMessage());
        }
    }

    public record CdpArgs(
        @ToolParam(description = "JavaScript expression to evaluate in the browser") String expression
    ) {}
}
