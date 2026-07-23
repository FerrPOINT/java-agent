package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_console",
    description = "Evaluate a JavaScript expression in the browser and return the result.",
    toolset = "browser"
)
@Component
public class BrowserConsoleTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserConsoleTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ConsoleArgs args = ToolHandler.parseJson(arguments, ConsoleArgs.class);
        try {
            return ToolResult.ok(browserService.evaluate(args.expression()));
        } catch (Exception e) {
            return ToolResult.fail("Browser console failed: " + e.getMessage());
        }
    }

    public record ConsoleArgs(
        @ToolParam(description = "JavaScript expression to evaluate") String expression
    ) {}
}
