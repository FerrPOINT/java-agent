package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_vision",
    description = "Capture a screenshot of the current page and return it as a base64 PNG data URL.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserVisionTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            return ToolResult.ok(browserService.screenshot());
        } catch (Exception e) {
            return ToolResult.fail("Browser screenshot failed: " + e.getMessage());
        }
    }
}
