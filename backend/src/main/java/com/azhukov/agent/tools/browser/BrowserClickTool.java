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
    description = "Click an element identified by a ref ID from browser_snapshot, for example @e5.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserClickTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ClickArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ClickArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        String target = args.target();
        if (target == null || target.isBlank()) {
            return BrowserToolResponses.failureResult("ref is required");
        }
        try {
            String result = browserService.click(target);
            if (BrowserToolResponses.looksLikeFailure(result)) {
                return BrowserToolResponses.failureResult(result);
            }
            return ToolResult.ok(BrowserToolResponses.success("clicked", displayRef(target)));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser click failed: " + e.getMessage());
        }
    }

    private String displayRef(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }
        String trimmed = target.trim();
        return trimmed.startsWith("@") || trimmed.matches("e\\d+") ? "@" + trimmed.replaceFirst("^@", "") : trimmed;
    }

    public record ClickArgs(
        @ToolParam(description = "element reference from browser_snapshot, for example @e5") String ref,
        @ToolParam(description = "legacy CSS selector fallback", required = false) String selector
    ) {
        String target() {
            return ref != null && !ref.isBlank() ? ref : selector;
        }
    }
}
