package com.azhukov.agent.tools.web;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

@AgentTool(
    name = "web_search",
    description = "Search the web and return a list of relevant results with titles, URLs, and snippets.",
    toolset = "web"
)
@Component
public class WebSearchTool implements ToolHandler {

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        return ToolResult.ok("Web search is not yet implemented. Query: " + arguments);
    }

    public record SearchArgs(
        @ToolParam(description = "search query") String query,
        @ToolParam(description = "maximum number of results") int limit
    ) {}
}
