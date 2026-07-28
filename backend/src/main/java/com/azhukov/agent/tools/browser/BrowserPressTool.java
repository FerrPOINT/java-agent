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
    name = "browser_press",
    description = "Press a keyboard key in the browser (e.g. Enter, Escape, ArrowDown).",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserPressTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        PressArgs args = ToolHandler.parseJson(arguments, PressArgs.class);
        try {
            String key = args.key().replace("'", "\\'");
            String script = "document.dispatchEvent(new KeyboardEvent('keydown', { key: '" + key + "', bubbles: true }));";
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser press failed: " + e.getMessage());
        }
    }

    public record PressArgs(
        @ToolParam(description = "Key name to press") String key
    ) {}
}
