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
    description = "Scroll the browser page. Accepts Hermes-compatible direction (up/down) or explicit x/y deltas.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserScrollTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ScrollArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ScrollArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        try {
            String direction = normalizedDirection(args.direction());
            if (direction == null && args.direction() != null && !args.direction().isBlank()) {
                return BrowserToolResponses.failureResult(
                    "Invalid direction '" + args.direction() + "'. Use 'up' or 'down'.");
            }
            boolean explicitDeltas = args.x() != null || args.y() != null;
            if (!explicitDeltas && direction == null) {
                direction = "down";
            }
            int x = args.x() != null ? args.x() : 0;
            int y = args.y() != null ? args.y() : directionDelta(direction);
            x = Math.max(-100000, Math.min(100000, x));
            y = Math.max(-100000, Math.min(100000, y));
            String script = "(() => { window.scrollBy(" + x + ", " + y + "); return { x: window.scrollX, y: window.scrollY }; })()";
            String result = browserService.evaluate(script);
            if (BrowserToolResponses.looksLikeFailure(result)) {
                return BrowserToolResponses.failureResult(result);
            }
            if (!explicitDeltas && direction != null) {
                return ToolResult.ok(BrowserToolResponses.success("scrolled", direction));
            }
            return ToolResult.ok(result);
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser scroll failed: " + e.getMessage());
        }
    }

    private String normalizedDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        return switch (direction.trim().toLowerCase()) {
            case "up" -> "up";
            case "down" -> "down";
            default -> null;
        };
    }

    private int directionDelta(String direction) {
        return switch (direction) {
            case "up" -> -500;
            case "down" -> 500;
            default -> 0;
        };
    }

    public record ScrollArgs(
        @ToolParam(description = "horizontal delta", required = false) Integer x,
        @ToolParam(description = "vertical delta", required = false) Integer y,
        @ToolParam(description = "Hermes-compatible scroll direction: up or down", required = false) String direction
    ) {}
}
