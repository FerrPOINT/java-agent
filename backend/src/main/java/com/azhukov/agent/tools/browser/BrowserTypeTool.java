package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_type",
    description = "Type text into a focused input element or the first matching selector.",
    toolset = "browser"
)
@Component
public class BrowserTypeTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserTypeTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TypeArgs args = ToolHandler.parseJson(arguments, TypeArgs.class);
        try {
            String selector = args.selector() != null ? args.selector() : "document.activeElement";
            String text = args.text().replace("'", "\\'");
            String script = "const el = " + selector + "; if (el) { el.value += '" + text + "'; el.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; } return 'no element';";
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser type failed: " + e.getMessage());
        }
    }

    public record TypeArgs(
        @ToolParam(description = "text to type") String text,
        @ToolParam(description = "CSS selector; omit to use focused element", required = false) String selector,
        @ToolParam(description = "clear field before typing", required = false) boolean clear
    ) {}
}
