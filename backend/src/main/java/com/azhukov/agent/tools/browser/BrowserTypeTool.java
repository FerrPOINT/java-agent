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
    name = "browser_type",
    description = "Type text into an input field identified by a ref ID from browser_snapshot.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserTypeTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TypeArgs args;
        try {
            args = ToolHandler.parseJson(arguments, TypeArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        if (args.text() == null) {
            return BrowserToolResponses.failureResult("text is required");
        }
        String target = args.target();
        if (target == null || target.isBlank()) {
            return BrowserToolResponses.failureResult("ref is required");
        }
        try {
            String result = browserService.type(target, args.text(), args.shouldClear());
            if (BrowserToolResponses.looksLikeFailure(result)) {
                return BrowserToolResponses.failureResult(result);
            }
            return ToolResult.ok(BrowserToolResponses.success("typed", args.text(), "element", displayRef(target)));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser type failed: " + e.getMessage());
        }
    }

    private String displayRef(String target) {
        if (target == null || target.isBlank()) {
            return "";
        }
        String trimmed = target.trim();
        return trimmed.startsWith("@") || trimmed.matches("e\\d+") ? "@" + trimmed.replaceFirst("^@", "") : trimmed;
    }

    public record TypeArgs(
        @ToolParam(description = "element reference from browser_snapshot, for example @e3") String ref,
        @ToolParam(description = "text to type") String text,
        @ToolParam(description = "legacy CSS selector fallback", required = false) String selector,
        @ToolParam(description = "clear field before typing, default true like Hermes", required = false) Boolean clear
    ) {
        String target() {
            return ref != null && !ref.isBlank() ? ref : selector;
        }

        boolean shouldClear() {
            return clear == null || clear;
        }
    }
}
