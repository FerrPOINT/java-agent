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
    name = "browser_scroll",
    description = "Scroll the browser page vertically or horizontally.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserScrollTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ScrollArgs args = ToolHandler.parseJson(arguments, ScrollArgs.class);
        try {
            int x = args.x() != null ? Math.max(-100000, Math.min(100000, args.x())) : 0;
            int y = args.y() != null ? Math.max(-100000, Math.min(100000, args.y())) : 0;
            String script = "window.scrollBy(" + x + ", " + y + "); return JSON.stringify({ x: window.scrollX, y: window.scrollY });";
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser scroll failed: " + e.getMessage());
        }
    }

    public record ScrollArgs(
        @ToolParam(description = "horizontal delta", required = false) Integer x,
        @ToolParam(description = "vertical delta", required = false) Integer y
    ) {}
}