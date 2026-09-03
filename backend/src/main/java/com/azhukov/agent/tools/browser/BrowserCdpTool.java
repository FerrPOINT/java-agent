package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_cdp",
    description = "Send a raw Chrome DevTools Protocol command and return the result. Provide method like 'Target.getTargets' or 'Runtime.evaluate' and optional params as a JSON object.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserCdpTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        CdpArgs args;
        try {
            args = ToolHandler.parseJson(arguments, CdpArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        if ((args.method() == null || args.method().isBlank())
            && args.expression() != null && !args.expression().isBlank()) {
            return evaluateLegacyExpression(args.expression());
        }
        if (args.method() == null || args.method().isBlank()) {
            return BrowserToolResponses.failureResult("method is required");
        }
        if (args.targetId() != null && !args.targetId().isBlank()) {
            return BrowserToolResponses.failureResult("browser_cdp target_id is not supported by the Java browser backend yet; omit target_id to use the current page target.");
        }
        if (args.frameId() != null && !args.frameId().isBlank()) {
            return BrowserToolResponses.failureResult("browser_cdp frame_id is not supported by the Java browser backend yet; omit frame_id to use the current page target.");
        }
        try {
            String result = browserService.rawCdp(args.method().trim(), args.params(), args.timeout());
            return ToolResult.ok(result);
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser CDP command failed: " + e.getMessage());
        }
    }

    private ToolResult evaluateLegacyExpression(String expression) {
        try {
            return ToolResult.ok(browserService.evaluate(expression));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser CDP evaluate failed: " + e.getMessage());
        }
    }

    public record CdpArgs(
        @ToolParam(description = "CDP method name, e.g. Target.getTargets, Runtime.evaluate, Page.handleJavaScriptDialog") String method,
        @ToolParam(description = "Method-specific CDP parameters as a JSON object", required = false, type = "object") JsonNode params,
        @JsonProperty("target_id")
        @JsonAlias("targetId")
        @ToolParam(description = "Hermes-compatible target id. Not supported by the Java backend yet.", required = false) String targetId,
        @JsonProperty("frame_id")
        @JsonAlias("frameId")
        @ToolParam(description = "Hermes-compatible frame id. Not supported by the Java backend yet.", required = false) String frameId,
        @ToolParam(description = "Timeout in seconds, default 30, max 300", required = false) int timeout,
        @ToolParam(description = "Legacy Java fallback: JavaScript expression to evaluate when method is omitted", required = false) String expression
    ) {}
}
