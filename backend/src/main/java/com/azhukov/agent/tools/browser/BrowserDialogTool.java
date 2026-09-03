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
    name = "browser_dialog",
    description = "Accept, dismiss, or answer a browser JavaScript dialog (alert/confirm/prompt).",
    toolset = "browser"
)
@Component
@RequiredArgsConstructor
public class BrowserDialogTool implements ToolHandler {

    private final BrowserService browserService;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DialogArgs args;
        try {
            args = ToolHandler.parseJson(arguments, DialogArgs.class);
        } catch (IllegalArgumentException e) {
            return BrowserToolResponses.failureResult(e.getMessage());
        }
        if (args.action() == null || args.action().isBlank()) {
            return BrowserToolResponses.failureResult("action is required");
        }
        if (args.dialogId() != null && !args.dialogId().isBlank()) {
            return BrowserToolResponses.failureResult("browser_dialog dialog_id is not supported by the Java browser backend yet; omit dialog_id to respond to the active dialog.");
        }
        try {
            String action = args.action();
            return switch (action.toLowerCase()) {
                case "dismiss" -> ToolResult.ok(browserService.handleDialog(false, args.promptText()));
                case "accept" -> ToolResult.ok(browserService.handleDialog(true, args.promptText()));
                default -> BrowserToolResponses.failureResult("Unknown dialog action: " + action);
            };
        } catch (Exception e) {
            return BrowserToolResponses.failureResult("Browser dialog failed: " + e.getMessage());
        }
    }

    public record DialogArgs(
        @ToolParam(description = "accept or dismiss") String action,
        @JsonProperty("prompt_text")
        @JsonAlias("text")
        @ToolParam(description = "text for prompt dialogs", required = false) String promptText,
        @JsonProperty("dialog_id")
        @JsonAlias("dialogId")
        @ToolParam(description = "Hermes-compatible dialog id. Not supported by the Java backend yet.", required = false) String dialogId
    ) {}
}
