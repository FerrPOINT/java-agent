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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the system prompt in three tiers for cache-friendliness:
 * <ul>
 *   <li><b>stable</b> — identity, rules, tool guidance (never changes per session)</li>
 *   <li><b>context</b> — session info, skills index, coding context (changes on session events only)</li>
 *   <li><b>volatile</b> — timestamp, dynamic context (changes per turn — but NOT memory,
 *       which is prepended fresh each turn via {@link #buildMemoryPrefix(Session)})</li>
 * </ul>
 * The system prompt is built once per session and cached via {@link PromptCacheTracker}.
 * Only context compression triggers a rebuild.
 */
@Slf4j
@Component
public class DefaultPromptBuilder implements PromptBuilder {

    /**
     * Models that use the OpenAI 'developer' role instead of 'system' for the
     * system prompt message (e.g. GPT-5, Codex). When the configured model name
     * starts with any of these prefixes, {@link #buildSystemMessage} will emit
     * a {@link Role#DEVELOPER} message instead of {@link Role#SYSTEM}.
     */
    static final Set<String> DEVELOPER_ROLE_MODELS = Set.of("gpt-5", "codex");

    // ── Model family detection prefixes (Fix 9: per-model operational guidance) ──

    /** Model name prefixes indicating OpenAI family (GPT, o1/o3 reasoning, Codex). */
    static final Set<String> OPENAI_FAMILY_PREFIXES = Set.of("gpt", "o1", "o3", "codex");

    /** Model name prefixes indicating Google family (Gemini, Gemma). */
    static final Set<String> GOOGLE_FAMILY_PREFIXES = Set.of("gemini", "gemma");

    /** Placeholder used when prompt injection is detected in context file content. */
    static final String INJECTION_PLACEHOLDER = "[content removed: potential prompt injection]";

    /**
     * Common prompt-injection patterns found in context files (AGENTS.md, .cursorrules, SOUL.md).
     * Matches are case-insensitive. Used by {@link #scanContextContent}.
     */
    static final List<Pattern> THREAT_PATTERNS = List.of(
        Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
        Pattern.compile("(?i)system\\s*prompt\\s*[:)]"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s"),
        Pattern.compile("(?i)</\\s*system\\s*>"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous\\s+)?(instructions|rules)"),
        Pattern.compile("(?i)forget\\s+(everything|all\\s+(previous\\s+)?instructions)"),
        Pattern.compile("(?i)new\\s+instructions?\\s*[:)]"),
        Pattern.compile("(?i)override\\s+(system|previous|all)\\s+(instructions?|rules|prompts?)")
    );

    /**
     * Operational guidance for OpenAI models (GPT, o1/o3, Codex).
     * Injected into the system prompt when the configured model belongs to the OpenAI family.
     */
    static final String OPENAI_MODEL_GUIDANCE = """
        ## Model-Specific Guidance (OpenAI)
        - **Tool persistence**: Once you start using a tool, continue using tools until the task is complete. Do not fall back to narration.
        - **Act, don't ask**: When you have enough context to act, call tools immediately. Do not ask for permission to use tools that are already available.
        - **Prerequisite checks**: Before calling a tool, verify its preconditions (e.g., file exists, dependencies installed). Use other tools to check first.
        - **Verification before claiming done**: After performing actions, verify the results by reading output, running tests, or checking state. Never claim a task is complete without verification.
        - **Missing context**: If you lack information needed to proceed, use tools to gather it rather than asking the user. Only ask when tool-based discovery is impossible.""";

    /**
     * Operational guidance for Google models (Gemini, Gemma).
     * Injected into the system prompt when the configured model belongs to the Google family.
     */
    static final String GOOGLE_MODEL_GUIDANCE = """
        ## Model-Specific Guidance (Google)
        - **Absolute paths**: Always use absolute file paths in tool calls. Relative paths may not resolve correctly.
        - **Verify first**: Before making changes, verify the current state by reading files or checking existing output. Do not assume state from prior context.
        - **Dependency checks**: Before running builds, tests, or scripts, verify required dependencies are installed and available.
        - **Conciseness**: Keep responses concise. Avoid restating the task or summarizing what you will do — just do it.
        - **Parallel tool calls**: When multiple independent tool calls are needed, batch them in a single turn for efficiency.""";

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
     * <p>
     * Memory is injected as a prefix to the system prompt (not the user message) so that
     * the model always has persistent facts in context. The three-tier prompt itself is
     * cached via {@link PromptCacheTracker} and remains byte-stable; the memory prefix
     * is fetched fresh each turn and prepended after the cache lookup, so the cache is
     * preserved when memory is unchanged and only invalidated when it actually changes.
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

        // Prepend memory prefix to the system prompt (not the user message) so that
        // persistent facts are always in context. The three-tier prompt cached above
        // remains byte-stable; only the memory prefix is fetched fresh per turn.
        String memoryPrefix = buildMemoryPrefix(session);
        if (!memoryPrefix.isEmpty()) {
            text = memoryPrefix + "\n\n" + text;
        }

        // Track system prompt hash for cache validation
        if (cacheTracker != null && session != null && session.id() != null) {
            String prefixHash = PromptCacheTracker.hashPrefix(text);
            if (cacheTracker.isCacheValid(String.valueOf(session.id()), prefixHash)) {
                // Cache is valid — system prompt unchanged from previous turn
            } else {
                cacheTracker.markCached(String.valueOf(session.id()), prefixHash);
            }
        }

        return usesDeveloperRole()
            ? Message.developer(text)
            : Message.system(text);
    }

    /**
     * Check whether the configured model expects the OpenAI 'developer' role
     * instead of 'system' (e.g. GPT-5, Codex).
     */
    private boolean usesDeveloperRole() {
        String modelName = properties.getModel().getModelName();
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return DEVELOPER_ROLE_MODELS.stream().anyMatch(lower::startsWith);
    }

    /**
     * Detect the model family from the configured model name.
     *
     * @return "openai" for GPT/o1/o3/Codex models, "google" for Gemini/Gemma models,
     *         or null for unrecognized families.
     */
    String detectModelFamily() {
        String modelName = properties.getModel().getModelName();
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String lower = modelName.toLowerCase();
        if (OPENAI_FAMILY_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return "openai";
        }
        if (GOOGLE_FAMILY_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return "google";
        }
        return null;
    }

    /**
     * Get the operational guidance text for the configured model family,
     * or empty string if the family is unrecognized.
     */
    String getModelGuidance() {
        String family = detectModelFamily();
        if (family == null) {
            return "";
        }
        return switch (family) {
            case "openai" -> OPENAI_MODEL_GUIDANCE;
            case "google" -> GOOGLE_MODEL_GUIDANCE;
            default -> "";
        };
    }

    /**
     * Scan context file content for prompt injection patterns.
     * If detected, the offending content is replaced with a placeholder and a warning is logged.
     *
     * @param content  the raw content of the context file (e.g. AGENTS.md, .cursorrules, SOUL.md)
     * @param fileName the file name for logging purposes
     * @return the content with prompt injection patterns replaced by a placeholder
     */
    String scanContextContent(String content, String fileName) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String result = content;
        boolean detected = false;
        for (Pattern pattern : THREAT_PATTERNS) {
            if (pattern.matcher(result).find()) {
                result = pattern.matcher(result).replaceAll(INJECTION_PLACEHOLDER);
                detected = true;
            }
        }
        if (detected) {
            log.warn("Potential prompt injection detected in context file '{}'; offending content replaced with placeholder", fileName);
        }
        return result;
    }

    /**
     * Build a memory prefix that is prepended to the system prompt.
     * The three-tier prompt is cached separately via {@link PromptCacheTracker} and
     * remains byte-stable; this prefix is fetched fresh each turn and prepended after
     * the cache lookup, so prompt cache is preserved when memory content is unchanged.
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

        // ── Model-specific operational guidance (Fix 9) ──
        String modelGuidance = getModelGuidance();
        if (!modelGuidance.isEmpty()) {
            stable.append("\n").append(modelGuidance);
        }

        // ── Context tier (changes on session events only) ──
        StringBuilder contextTier = new StringBuilder();
        if (systemMessageOverride != null && !systemMessageOverride.isBlank()) {
            // Fix 10: Scan context file content for prompt injection before injection
            String scanned = scanContextContent(systemMessageOverride, "systemMessageOverride");
            contextTier.append(scanned).append("\n\n");
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