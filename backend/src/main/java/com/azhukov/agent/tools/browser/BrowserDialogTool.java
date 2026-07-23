package com.azhukov.agent.tools.browser;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "browser_dialog",
    description = "Accept, dismiss, or answer a browser JavaScript dialog (alert/confirm/prompt).",
    toolset = "browser"
)
@Component
public class BrowserDialogTool implements ToolHandler {

    private final BrowserService browserService;

    public BrowserDialogTool(BrowserService browserService) {
        this.browserService = browserService;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DialogArgs args = ToolHandler.parseJson(arguments, DialogArgs.class);
        try {
            String action = args.action() != null ? args.action() : "accept";
            return switch (action.toLowerCase()) {
                case "dismiss" -> ToolResult.ok(browserService.evaluate("window.__agent_dialog && window.__agent_dialog.dismiss()"));
                case "accept" -> ToolResult.ok(browserService.evaluate("window.__agent_dialog && window.__agent_dialog.accept()"));
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
