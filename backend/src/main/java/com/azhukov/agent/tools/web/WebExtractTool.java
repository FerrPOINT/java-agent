package com.azhukov.agent.tools.web;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "web_extract",
    description = "Extract text content from one or more web page URLs.",
    toolset = "web"
)
@Component
public class WebExtractTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        return ToolResult.ok("Web extract is not yet implemented. URLs: " + arguments);
    }

    public record ExtractArgs(
        @ToolParam(description = "URL or comma-separated URLs to extract") String urls
    ) {}
}
