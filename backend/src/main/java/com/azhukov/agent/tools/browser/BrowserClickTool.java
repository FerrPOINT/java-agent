package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_click",
    description = "Click an element in the browser by CSS selector.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserClickTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClickArgs args = ToolHandler.parseJson(arguments, ClickArgs.class);
        try {
            String result = browserService.click(args.selector());
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.fail("Browser click failed: " + e.getMessage());
        }
    }

    public record ClickArgs(
        @ToolParam(description = "CSS selector of the element to click") String selector
    ) {}
}
