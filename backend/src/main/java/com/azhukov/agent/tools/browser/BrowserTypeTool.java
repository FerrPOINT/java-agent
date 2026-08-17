package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_type",
    description = "Type text into a focused input element or the first matching selector.",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserTypeTool implements ToolHandler {

    private final BrowserService browserService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        TypeArgs args = ToolHandler.parseJson(arguments, TypeArgs.class);
        try {
            String safeText = MAPPER.writeValueAsString(args.text());
            String clearPrefix = args.clear() ? "el.value = ''; " : "";
            String script;
            if (args.selector() != null && !args.selector().isBlank()) {
                // CSS selector path: use querySelector with JSON.stringify for safe escaping
                String safeSelector = MAPPER.writeValueAsString(args.selector());
                script = "const el = document.querySelector(" + safeSelector + "); if (el) { "
                    + clearPrefix + "el.value += " + safeText
                    + "; el.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; } return 'no element';";
            } else {
                // Default: use focused element (no selector injection risk)
                script = "const el = document.activeElement; if (el) { "
                    + clearPrefix + "el.value += " + safeText
                    + "; el.dispatchEvent(new Event('input', { bubbles: true })); return 'typed'; } return 'no element';";
            }
            return ToolResult.ok(browserService.evaluate(script));
        } catch (Exception e) {
            return ToolResult.fail("Browser type failed: " + e.getMessage());
        }
    }

    public record TypeArgs(
        @ToolParam(description = "text to type") String text,
        @ToolParam(description = "CSS selector; omit to use focused element", required = false) String selector,
        @ToolParam(description = "clear field before typing", required = false) boolean clear
    ) {}
}