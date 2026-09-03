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
    name = "browser_snapshot",
    description = "Return an accessibility-style page snapshot with interactive element ref IDs for browser_click and browser_type.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserSnapshotTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SnapshotArgs args;
        try {
            args = ToolHandler.parseJson(arguments, SnapshotArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        try {
            boolean full = args.isFull();
            String result = browserService.accessibilitySnapshot(full);
            return ToolResult.ok(result);
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser snapshot failed: " + e.getMessage());
        }
    }

    public record SnapshotArgs(
        @ToolParam(description = "if true, return complete page content; if false, compact view", required = false) Boolean full
    ) {
        public boolean isFull() { return full != null && full; }
    }
}
