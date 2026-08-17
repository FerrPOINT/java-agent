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
    name = "browser_dialog",
    description = "Accept, dismiss, or answer a browser JavaScript dialog (alert/confirm/prompt).",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserDialogTool implements ToolHandler {

    private final BrowserService browserService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DialogArgs args = ToolHandler.parseJson(arguments, DialogArgs.class);
        try {
            String action = args.action() != null ? args.action() : "accept";
            return switch (action.toLowerCase()) {
                case "dismiss" -> ToolResult.ok(browserService.handleDialog(false, args.text()));
                case "accept" -> ToolResult.ok(browserService.handleDialog(true, args.text()));
                default -> ToolResult.fail("Unknown dialog action: " + action);
            };
        } catch (Exception e) {
            return ToolResult.fail("Browser dialog failed: " + e.getMessage());
        }
    }

    public record DialogArgs(
        @ToolParam(description = "accept or dismiss") String action,
        @ToolParam(description = "text for prompt dialogs", required = false) String text
    ) {}
}