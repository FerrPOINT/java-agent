package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.springframework.stereotype.Component;

public class DefaultPromptBuilder implements PromptBuilder {

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;
    private final AgentConstants constants;
    private final PromptCacheTracker cacheTracker;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry) {
        this(properties, toolRegistry, new DefaultAgentConstants(), null);
    }

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants) {
        this(properties, toolRegistry, constants, null);
    }

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants, PromptCacheTracker cacheTracker) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.constants = constants;
        this.cacheTracker = cacheTracker;
    }

    @Override
    public Message buildSystemMessage(Session session) {
        String text = properties.getCore().getDefaultSystemPrompt();
        if (text == null || text.isBlank()) {
            text = buildDefaultPrompt();
        }
        text = text.replace("${agent.name}", constants.resolve("agent.name"));
        // Track system prompt hash for cache validation
        if (cacheTracker != null && session != null && session.id() != null) {
            String prefixHash = PromptCacheTracker.hashPrefix(text);
            if (cacheTracker.isCacheValid(String.valueOf(session.id()), prefixHash)) {
                // Cache is valid — system prompt unchanged from previous turn
            } else {
                cacheTracker.markCached(String.valueOf(session.id()), prefixHash);
            }
        }
        return Message.system(text);
    }

    private String buildDefaultPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(properties.getName()).append(".\n\n");
        sb.append("Available toolsets:\n");
        for (String toolset : toolRegistry.getToolsets()) {
            sb.append("- ").append(toolset).append("\n");
        }
        sb.append("\nRules:\n");
        sb.append("1. Use tools when they help answer the user.\n");
        sb.append("2. Be concise and actionable.\n");
        sb.append("3. Do not invent facts; use web_search/browser when unsure.\n");
        sb.append("4. For file edits use write_file/patch; for searches use search_files.\n");
        sb.append("5. Dangerous terminal commands require user approval; respect the result.\n");
        sb.append("6. When delegating, keep sub-tasks focused and small.\n");
        sb.append("7. Prefer skills when a matching skill is available.\n");
        sb.append("8. If the user asks to open a page or take a screenshot, call browser_navigate and/or browser_vision.\n");
        return sb.toString();
    }
}
