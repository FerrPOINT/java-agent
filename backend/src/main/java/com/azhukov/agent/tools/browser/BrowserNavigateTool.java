package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_navigate",
    description = "Navigate browser to a URL, wait for load, and return a compact snapshot with element refs.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserNavigateTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        NavigateArgs args;
        try {
            args = ToolHandler.parseJson(arguments, NavigateArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        if (args.url() == null || args.url().isBlank()) {
            return BrowserToolResponses.failureResult("url is required");
        }
        try {
            int waitSeconds = args.waitSeconds() > 0 ? args.waitSeconds() : 30;
            String result = browserService.navigate(args.url(), waitSeconds);
            if (BrowserToolResponses.looksLikeFailure(result)) {
                return BrowserToolResponses.failureResult(result);
            }
            return ToolResult.ok(appendCompactSnapshot(result));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser navigate failed: " + e.getMessage());
        }
    }

    private String appendCompactSnapshot(String navigationResult) {
        if (navigationResult == null || !navigationResult.startsWith("Navigated to ")) {
            return navigationResult;
        }
        try {
            String snapshot = browserService.accessibilitySnapshot(false);
            if (snapshot == null || snapshot.isBlank()) {
                return navigationResult;
            }
            return navigationResult + "\n\nSnapshot:\n" + snapshot;
        } catch (Exception ignored) {
            return navigationResult;
        }
    }

    public record NavigateArgs(
        @ToolParam(description = "URL to navigate to") String url,
        @ToolParam(description = "wait for load timeout in seconds", required = false) @JsonProperty("wait_seconds") @JsonAlias("waitSeconds") int waitSeconds
    ) {}
}
