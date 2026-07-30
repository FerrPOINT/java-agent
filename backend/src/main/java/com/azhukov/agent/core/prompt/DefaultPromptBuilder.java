package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.context.CodingContextDetector;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.AgentConstants;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the system prompt in three tiers for cache-friendliness:
 * <ul>
 *   <li><b>stable</b> — identity, rules, tool guidance (never changes per session)</li>
 *   <li><b>context</b> — session info, skills index, coding context (changes on session events only)</li>
 *   <li><b>volatile</b> — timestamp, dynamic context (changes per turn — but NOT memory,
 *       which is moved to user message prefix to preserve cache)</li>
 * </ul>
 * The system prompt is built once per session and cached via {@link PromptCacheTracker}.
 * Only context compression triggers a rebuild.
 */
@Slf4j
@Component
public class DefaultPromptBuilder implements PromptBuilder {

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;
    private final AgentConstants constants;
    private final PromptCacheTracker cacheTracker;
    private final CodingContextDetector codingContextDetector;
    private final MemoryProvider memoryProvider;

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

    @Autowired
    public DefaultPromptBuilder(AgentProperties properties, ToolRegistry toolRegistry, AgentConstants constants, PromptCacheTracker cacheTracker, CodingContextDetector codingContextDetector, MemoryProvider memoryProvider) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.constants = constants;
        this.cacheTracker = cacheTracker;
        this.codingContextDetector = codingContextDetector;
        this.memoryProvider = memoryProvider;
    }

    @Override
    public Message buildSystemMessage(Session session) {
        return buildSystemMessage(session, null);
    }

    /**
     * Build the system message with three-tier composition and session-level caching.
     * Memory is NOT injected into the system prompt — it's available as a user message prefix
     * via {@link #buildMemoryPrefix(Session)} to preserve prompt cache stability.
     */
    public Message buildSystemMessage(Session session, String systemMessageOverride) {
        String sessionId = session != null && session.id() != null ? String.valueOf(session.id()) : "default";

        PromptCacheTracker.CachedSystemPrompt cached = null;
        if (cacheTracker != null) {
            cached = cacheTracker.getOrBuild(sessionId, () -> buildThreeTierPrompt(session, systemMessageOverride));
        } else {
            cached = buildThreeTierPrompt(session, systemMessageOverride);
        }

        String text = cached.fullPrompt();

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

    /**
     * Build a memory prefix that should be prepended to the user message, not the system prompt.
     * This preserves the prompt cache by keeping the system prompt byte-stable.
     */
    public String buildMemoryPrefix(Session session) {
        if (session == null || session.id() == null || memoryProvider == null) {
            return "";
        }
        try {
            var memories = memoryProvider.recall(session.userId(), "", 20);
            if (memories == null || memories.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## Memory (persistent facts)\n");
            for (String memory : memories) {
                sb.append("- ").append(memory).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("Memory prefix build failed: {}", e.getMessage());
            return "";
        }
    }

    private PromptCacheTracker.CachedSystemPrompt buildThreeTierPrompt(Session session, String systemMessageOverride) {
        // ── Stable tier (never changes per session) ──
        StringBuilder stable = new StringBuilder();
        stable.append("You are ").append(properties.getName()).append(", an autonomous AI agent.\n\n");
        stable.append("## Rules\n");
        stable.append("1. **Use tools actively** — don't just talk about what you could do, actually call tools to accomplish the task.\n");
        stable.append("2. **Be concise and actionable** — deliver real results, not descriptions of results.\n");
        stable.append("3. **Don't invent facts** — use web_search/browser when unsure.\n");
        stable.append("4. **File operations** — use write_file/patch for edits, search_files for searches.\n");
        stable.append("5. **Dangerous commands** — require user approval; respect the result.\n");
        stable.append("6. **Delegation** — keep sub-tasks focused and small.\n");
        stable.append("7. **Skills** — prefer skills when a matching skill is available.\n");
        stable.append("8. **Browser** — if the user asks to open a page or take a screenshot, call browser_navigate and/or browser_vision.\n");
        stable.append("9. **Task completion** — after completing work, verify your output. Report what real execution returned, not what you planned to do.\n");
        stable.append("10. **Parallel tool calls** — when multiple independent tools can run in parallel, call them together.\n");
        stable.append("11. **Error handling** — if a tool fails, try an alternative approach. Never fabricate results.\n");

        // ── Context tier (changes on session events only) ──
        StringBuilder contextTier = new StringBuilder();
        if (systemMessageOverride != null && !systemMessageOverride.isBlank()) {
            contextTier.append(systemMessageOverride).append("\n\n");
        }

        // Available toolsets and tools
        contextTier.append("## Available Toolsets\n");
        for (String toolset : toolRegistry.getToolsets()) {
            contextTier.append("- ").append(toolset).append("\n");
        }
        contextTier.append("\n");

        // Tool descriptions
        contextTier.append("## Tool Descriptions\n");
        for (var def : toolRegistry.getDefinitions()) {
            contextTier.append("- **").append(def.name()).append("**: ");
            contextTier.append(def.description() != null ? def.description() : "No description").append("\n");
        }
        contextTier.append("\n");

        // Skills
        contextTier.append("## Available Skills\n");
        contextTier.append("Load matching skills with skill_view(name) before performing a task. ");
        contextTier.append("If a skill matches your task, follow its instructions.\n");

        // Coding context detection
        if (codingContextDetector != null
                && properties.getCodingContext() != null
                && properties.getCodingContext().isEnabled()) {
            String workingDir = properties.getCore().getWorkingDirectory();
            CodingContextDetector.CodingContext ctx = codingContextDetector.detect(workingDir);
            if (ctx.language() != null) {
                contextTier.append("\nDetected coding context: language=").append(ctx.language())
                    .append(", framework=").append(ctx.framework())
                    .append(", buildTool=").append(ctx.buildTool())
                    .append(", gitRepo=").append(ctx.isGitRepo());
            }
        }

        // ── Volatile tier (changes per turn, but NO memory) ──
        StringBuilder volatileTier = new StringBuilder();
        volatileTier.append("## Environment\n");
        volatileTier.append("- Operating System: ").append(System.getProperty("os.name"))
          .append(" ").append(System.getProperty("os.arch")).append("\n");
        volatileTier.append("- Java Version: ").append(System.getProperty("java.version")).append("\n");
        String workingDir = properties.getCore().getWorkingDirectory();
        if (workingDir != null && !workingDir.isBlank()) {
            volatileTier.append("- Working Directory: ").append(workingDir).append("\n");
        }
        // Date-only (not minute-precision) so the system prompt is byte-stable for the full day
        volatileTier.append("- Current Date: ").append(java.time.LocalDate.now());

        return PromptCacheTracker.CachedSystemPrompt.of(
            stable.toString().trim(),
            contextTier.toString().trim(),
            volatileTier.toString().trim()
        );
    }

    private String buildDefaultPrompt(Session session) {
        var cached = buildThreeTierPrompt(session, null);
        return cached.fullPrompt();
    }
}