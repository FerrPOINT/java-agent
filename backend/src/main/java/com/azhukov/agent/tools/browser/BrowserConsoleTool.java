package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_console",
    description = "Evaluate a JavaScript expression in the browser and return the result.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserConsoleTool implements ToolHandler {

    private static final ObjectMapper MAPPER = ToolHandler.TOOL_ARGS_MAPPER.copy();

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        ConsoleArgs args;
        try {
            args = ToolHandler.parseJson(arguments, ConsoleArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        try {
            if (args.expression() == null || args.expression().isBlank()) {
                return ToolResult.ok(browserService.console(args.clear()));
            }
            String rawResult = browserService.evaluate(args.expression());
            if (rawResult != null && rawResult.startsWith("Evaluation error:")) {
                return BrowserToolResponses.failureResult(rawResult);
            }
            return ToolResult.ok(consoleEvalResponse(rawResult));
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser console failed: " + e.getMessage());
        }
    }

    private static String consoleEvalResponse(String rawResult) throws JsonProcessingException {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", true);
        JsonNode parsed = tryParseJson(rawResult);
        if (parsed != null) {
            response.set("result", parsed);
            response.put("result_type", resultType(parsed));
        } else {
            response.put("result", rawResult);
            response.put("result_type", "string");
        }
        return MAPPER.writeValueAsString(response);
    }

    private static JsonNode tryParseJson(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(rawResult);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String resultType(JsonNode node) {
        if (node.isArray()) {
            return "array";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return "string";
    }

    public record ConsoleArgs(
        @ToolParam(description = "If true, clear console buffers after reading. Console buffers are not supported by the Java backend yet.", required = false) boolean clear,
        @ToolParam(description = "JavaScript expression to evaluate", required = false) String expression
    ) {}
}
