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
    name = "browser_back",
    description = "Navigate browser back in history.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserBackTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            return ToolResult.ok(browserService.evaluate("history.back()"));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser back failed: " + e.getMessage());
        }
    }
}
