package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.CodingContextDetector;
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
    private final CodingContextDetector codingContextDetector;
    private final com.azhukov.agent.core.memory.MemoryProvider memoryProvider;

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry) {
        this(properties, toolRegistry, new DefaultAgentConstants(), null, null, null);
    }

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants) {
        this(properties, toolRegistry, constants, null, null, null);
    }

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants, PromptCacheTracker cacheTracker) {
        this(properties, toolRegistry, constants, cacheTracker, null, null);
    }

    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants, PromptCacheTracker cacheTracker, CodingContextDetector codingContextDetector) {
        this(properties, toolRegistry, constants, cacheTracker, codingContextDetector, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants, PromptCacheTracker cacheTracker, CodingContextDetector codingContextDetector, com.azhukov.agent.core.memory.MemoryProvider memoryProvider) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.constants = constants;
        this.cacheTracker = cacheTracker;
        this.codingContextDetector = codingContextDetector;
        this.memoryProvider = memoryProvider;
    }

    @Override
    public Message buildSystemMessage(Session session) {
        String text = properties.getCore().getDefaultSystemPrompt();
        if (text == null || text.isBlank()) {
            text = buildDefaultPrompt(session);
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

        // Stage 15: Append detected coding context if enabled
        if (codingContextDetector != null
                && properties.getCodingContext() != null
                && properties.getCodingContext().isEnabled()) {
            String workingDir = properties.getCore().getWorkingDirectory();
            CodingContextDetector.CodingContext ctx = codingContextDetector.detect(workingDir);
            if (ctx.language() != null) {
                text = text + "\n\nDetected coding context: language=" + ctx.language()
                    + ", framework=" + ctx.framework()
                    + ", buildTool=" + ctx.buildTool()
                    + ", gitRepo=" + ctx.isGitRepo();
            }
        }

        return Message.system(text);
    }

    private String buildDefaultPrompt(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(properties.getName()).append(", an autonomous AI agent.\n\n");

        // Environment info
        sb.append("## Environment\n");
        sb.append("- Operating System: ").append(System.getProperty("os.name"))
          .append(" ").append(System.getProperty("os.arch")).append("\n");
        sb.append("- Java Version: ").append(System.getProperty("java.version")).append("\n");
        String workingDir = properties.getCore().getWorkingDirectory();
        if (workingDir != null && !workingDir.isBlank()) {
            sb.append("- Working Directory: ").append(workingDir).append("\n");
        }
        sb.append("- Current Date: ").append(java.time.LocalDate.now()).append("\n\n");

        // Available toolsets and tools
        sb.append("## Available Toolsets\n");
        for (String toolset : toolRegistry.getToolsets()) {
            sb.append("- ").append(toolset).append("\n");
        }
        sb.append("\n");

        // Tool descriptions
        sb.append("## Tool Descriptions\n");
        for (var def : toolRegistry.getDefinitions()) {
            sb.append("- **").append(def.name()).append("**: ");
            sb.append(def.description() != null ? def.description() : "No description").append("\n");
        }
        sb.append("\n");

        // Skills
        sb.append("## Available Skills\n");
        sb.append("Load matching skills with skill_view(name) before performing a task. ");
        sb.append("If a skill matches your task, follow its instructions.\n\n");

        // Memory injection
        if (session != null && session.id() != null) {
            try {
                var memories = memoryProvider.recall(session.userId(), "", 20);
                if (memories != null && !memories.isEmpty()) {
                    sb.append("## Memory (persistent facts)\n");
                    for (String memory : memories) {
                        sb.append("- ").append(memory).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                // Memory recall is optional — don't fail if unavailable
            }
        }

        // Rules
        sb.append("## Rules\n");
        sb.append("1. **Use tools actively** — don't just talk about what you could do, actually call tools to accomplish the task.\n");
        sb.append("2. **Be concise and actionable** — deliver real results, not descriptions of results.\n");
        sb.append("3. **Don't invent facts** — use web_search/browser when unsure.\n");
        sb.append("4. **File operations** — use write_file/patch for edits, search_files for searches.\n");
        sb.append("5. **Dangerous commands** — require user approval; respect the result.\n");
        sb.append("6. **Delegation** — keep sub-tasks focused and small.\n");
        sb.append("7. **Skills** — prefer skills when a matching skill is available.\n");
        sb.append("8. **Browser** — if the user asks to open a page or take a screenshot, call browser_navigate and/or browser_vision.\n");
        sb.append("9. **Task completion** — after completing work, verify your output. Report what real execution returned, not what you planned to do.\n");
        sb.append("10. **Parallel tool calls** — when multiple independent tools can run in parallel, call them together.\n");
        sb.append("11. **Error handling** — if a tool fails, try an alternative approach. Never fabricate results.\n");
        return sb.toString();
    }
}