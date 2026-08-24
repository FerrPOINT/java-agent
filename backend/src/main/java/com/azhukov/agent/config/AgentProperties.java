package com.azhukov.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class AgentProperties {

    /** Single identity used by every surface that has no auth context
     *  (CLI, REST defaults, background/cron sessions). The CLI /sessions
     *  bug was two hard-coded strings drifting apart ('user-1' vs 'default'). */
    public static final String DEFAULT_USER_ID = "user-1";

    private String name = "Джава агент";
    private final ModelProperties model = new ModelProperties();
    private final AuxiliaryProperties auxiliary = new AuxiliaryProperties();
    private final VisionProperties vision = new VisionProperties();
    private final BrowserProperties browser = new BrowserProperties();
    private final ChromiumProperties chromium = new ChromiumProperties();
    private final WebProperties web = new WebProperties();
    private final TerminalProperties terminal = new TerminalProperties();
    private final FileProperties file = new FileProperties();
    private final MemoryProperties memory = new MemoryProperties();
    private final SkillsProperties skills = new SkillsProperties();
    private final SessionSearchProperties sessionSearch = new SessionSearchProperties();
    private final ToolOutputProperties toolOutput = new ToolOutputProperties();
    private final ContextProperties context = new ContextProperties();
    private final DelegationProperties delegation = new DelegationProperties();
    private final McpProperties mcp = new McpProperties();
    private final GatewayProperties gateway = new GatewayProperties();
    private final SecurityProperties security = new SecurityProperties();
    private final CoreProperties core = new CoreProperties();
    private final BudgetProperties budget = new BudgetProperties();
    private final PromptCachingProperties promptCaching = new PromptCachingProperties();
    private final CheckpointProperties checkpoints = new CheckpointProperties();
    private final VerifyOnStopProperties verifyOnStop = new VerifyOnStopProperties();
    private final UsageProperties usage = new UsageProperties();
    private final ImageGenProperties imageGen = new ImageGenProperties();
    private final TtsProperties tts = new TtsProperties();
    private final TranscriptionProperties transcription = new TranscriptionProperties();
    private final CronProperties cron = new CronProperties();
    private final ErrorProperties error = new ErrorProperties();
    private final ProfileProperties profile = new ProfileProperties();

    // ── Commentary (interim assistant messages) ──
    // Mirrors Hermes interim_assistant_messages config (default true).
    // When true, visible text accompanying tool calls is emitted as an
    // interim "commentary" message before tool execution begins.
    private boolean commentaryEnabled = true;
    private final CodingContextProperties codingContext = new CodingContextProperties();
    private final ToolProperties tools = new ToolProperties();
    private final CompressionProperties compression = new CompressionProperties();
    private final CuratorProperties curator = new CuratorProperties();

    // ── Fallback chain: ordered list of alternate models/providers ──
    // Mirrors Hermes _fallback_chain — when the primary model fails after all
    // retries, the runtime switches to the next entry in this chain.
    // Each entry specifies provider, model, baseUrl, and apiKey.
    private List<FallbackConfig> fallbackChain = new ArrayList<>();

    public void setFallbackChain(List<FallbackConfig> chain) {
        this.fallbackChain.clear();
        if (chain != null) {
            this.fallbackChain.addAll(chain);
        }
    }

    @Getter @Setter
    public static class ModelProperties {
        private String provider = "openai-compatible";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 120;
        private int maxRetries = 3;
        private int maxTokens = 4096;
        private double temperature = 0.7;
        private final Map<String, String> headers = new HashMap<>();
        private int reasoningEffort = 70;
        private boolean fastMode = false;
        /** Hermes parity: reasoning_content echo-back for DeepSeek/Kimi/MiMo.
         *  When true, LangChain4j sends thinking/reasoning_content on assistant
         *  message replays. Default false — only enable for providers that
         *  require it (DeepSeek, Kimi, MiMo). */
        private boolean returnThinking = false;
        /** Wire field name for reasoning_content: "reasoning_content" (DeepSeek/Kimi/MiMo)
         *  or "thinking" (generic). Default "reasoning_content". */
        private String thinkingFieldName = "reasoning_content";
        /** Maximum size in bytes per image before shrinking (default 4 MB). */
        private int maxImageSizeBytes = 4 * 1024 * 1024;
        /** Maximum total image payload in bytes before shrinking (default 20 MB). */
        private int maxTotalImageSizeBytes = 20 * 1024 * 1024;
        /** JPEG quality (0.0–1.0) used when re-encoding shrunk images (default 0.85). */
        private double imageJpegQuality = 0.85;
    }

    @Getter @Setter
    public static class AuxiliaryProperties {
        private boolean enabled = false;
        private String provider = "openai-compatible";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 600;
        private int maxRetries = 3;
    }

    @Getter @Setter
    public static class VisionProperties {
        private String provider = "";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 600;
        private int maxRetries = 3;
        private boolean useAuxiliaryFirst = true;
    }

    @Getter @Setter
    public static class BrowserProperties {
        private String cdpUrl = "http://localhost:9222";
        private int defaultTimeoutMs = 120000;
        private int pageLoadTimeoutMs = 120000;
        private int maxTabs = 5;
        private boolean headless = true;
        private String executablePath = "";
    }

    @Getter @Setter
    public static class ChromiumProperties {
        private boolean autoStart = true;
        private boolean autoInstall = true;
        private String downloadUrl = "https://storage.googleapis.com/chromium-browser-snapshots";
        private String revision = "";
        private int launchTimeoutSeconds = 120;
        private boolean headless = true;
        private String executablePath = "";
        private String userDataDir = "";
        private final List<String> extraArgs = new ArrayList<>();
    }

    @Getter @Setter
    public static class WebProperties {
        private int searchResults = 5;
        private int extractTimeoutSeconds = 120;
        private int extractMaxChars = 100000;
        private String searchProvider = "ddg";
        /** Feature 1: SearXNG instance URL. If set, web_search uses SearXNG instead of DuckDuckGo. */
        private String searxngUrl = "";
        private final List<String> allowedDomains = new ArrayList<>();
        private final List<String> blockedDomains = new ArrayList<>();
    }

    @Getter @Setter
    public static class TerminalProperties {
        @jakarta.validation.constraints.Positive
        private int defaultTimeoutSeconds = 300;
        @jakarta.validation.constraints.Positive
        private int maxTimeoutSeconds = 1800;
        private boolean dockerEnabled = false;
        private final List<String> blockedCommands = new ArrayList<>();
        private final List<String> requireApprovalCommands = new ArrayList<>();
        /** Whether to block 'sudo' commands by default (default true). */
        private boolean blockSudo = true;
    }

    @Getter @Setter
    public static class FileProperties {
        private int readMaxChars = 100000;
        private int writeMaxChars = 100000;
        private final List<String> allowedPaths = new ArrayList<>();
        private final List<String> blockedPaths = new ArrayList<>();
        private final List<String> blockedExtensions = new ArrayList<>();
    }

    @Getter @Setter
    public static class MemoryProperties {
        private int maxFactsPerUser = 1000;
        private int maxFactsPerQuery = 10;
        private double similarityThreshold = 0.75;
        private boolean writeApproval = false;
        /** Maximum total characters for the "memory" store (default 2200). */
        private int memoryCharLimit = 2200;
        /** Maximum total characters for the "user" store (default 1375). */
        private int userCharLimit = 1375;
        /** How many user turns between memory reviews (0 = disabled, default 10). */
        private int nudgeInterval = 10;
        /** Enable/disable user profile in memory (default true). */
        private boolean userProfileEnabled = true;
        /** Minimum turns before flushing memory to persistent storage (default 6). */
        private int flushMinTurns = 6;
        private final BackgroundReviewProperties backgroundReview = new BackgroundReviewProperties();
    }

    @Getter @Setter
    public static class BackgroundReviewProperties {
        private boolean enabled = true;
        private int delayMs = 2000;
        /** Maximum number of review turns for the background review mini-conversation (default 8). */
        private int maxReviewTurns = 8;
    }

    @Getter @Setter
    public static class SkillsProperties {
        private boolean enabled = true;
        private int maxSkillsInPrompt = 20;
        private int maxCharsPerSkill = 4000;
        // Hermes parity: skills tools (skill_view/skills_list/skill_manage) are
        // DEFAULT-ON — Hermes enables ALL toolsets when none are explicitly
        // configured, and the skills guidance ("patch a stale skill
        // immediately", "save as a skill") requires skill_manage to be
        // reachable. It was missing from the whitelist entirely.
        private List<String> defaultToolsets = new ArrayList<>(List.of("web", "file", "browser", "terminal", "coding", "memory", "core", "delegation", "gateway", "todo", "skills"));
        // S6: External skill directories (expanded ~/ and ${VAR})
        private final List<String> externalDirs = new ArrayList<>();
        // S6: Disabled skills (global list)
        private final List<String> disabled = new ArrayList<>();
        // S6: Per-platform disabled skills: platform name -> list of skill names
        private final Map<String, List<String>> platformDisabled = new HashMap<>();
        // S6: Skill config values (skills.config.<key> = value)
        private final Map<String, Object> config = new HashMap<>();
        // S2: Template vars substitution enabled
        private boolean templateVars = true;
        // S2: Inline shell expansion enabled
        private boolean inlineShell = true;
        // S2: Inline shell timeout in seconds
        private int inlineShellTimeout = 30;
        /** How many tool-calling iterations between skill reviews (0 = disabled, default 10). */
        private int creationNudgeInterval = 10;
        /**
         * SIMPLIFIED skills hub: single GitHub repository used as the skill
         * source (Hermes routes 9 sources; we use one repo). Default points at
         * our own skills collection.
         */
        private String hubRepo = "https://github.com/FerrPOINT/skills";
        /** Optional GitHub token for a private hub repo (env HUB_GITHUB_TOKEN overrides). */
        private String hubToken;
        /** Require approval before writing skill files (default false). */
        private boolean writeApproval = false;
        /** Extra security checks for agent-created skills (default false). */
        private boolean guardAgentCreated = false;

        public void setExternalDirs(List<String> dirs) { this.externalDirs.clear(); this.externalDirs.addAll(dirs); }
        public void setDisabled(List<String> disabled) { this.disabled.clear(); this.disabled.addAll(disabled); }
    }

    @Getter @Setter
    public static class SessionSearchProperties {
        private int maxResults = 10;
        private int snippetChars = 200;
    }

    @Getter @Setter
    public static class ToolOutputProperties {
        private int maxChars = 16000;
        private int truncateWarningChars = 12000;
        private int timeoutSeconds = 300;
        private boolean includeTimestamps = true;
        /** Feature 8: Tool result persistence threshold in bytes (default 51200 = 50KB). */
        private int persistThresholdBytes = 51200;
        /** Feature 8: Per-turn aggregate budget in bytes (default 204800 = 200KB). */
        private int turnBudgetBytes = 204800;

        public int getTimeoutSecondsOrDefault(int fallback) { return timeoutSeconds > 0 ? timeoutSeconds : fallback; }
    }

    @Getter @Setter
    public static class ContextProperties {
        private int maxTokens = 16000;
        private int targetTokens = 12000;
        private int summaryChunkTokens = 2000;
        private int maxContextMessages = 50;
        /** Maximum tokens allowed for injected reference content; default is maxTokens / 4. */
        private int maxReferenceTokens = 0; // 0 → computed as maxTokens / 4 at runtime
        private int protectFirstN = 3;
        /** Number of trailing messages (recent context) to protect from compression. */
        private int protectLastN = 20;
        /** Target ratio for compression (0.0–1.0, default 0.20). */
        private double targetRatio = 0.20;
    }

    @Getter @Setter
    public static class DelegationProperties {
        private boolean enabled = true;
        /** Maximum delegation spawn depth (0 = parent, 1 = first child). Default 3. */
        private int maxDepth = 3;
        /** Maximum spawn depth — agents at depths 0..maxSpawnDepth-1 can spawn; maxSpawnDepth is the leaf floor. Default 1 (matches Hermes MAX_DEPTH). */
        private int maxSpawnDepth = 1;
        /** Maximum number of concurrent child subagents. Default 3. */
        private int maxConcurrentChildren = 3;
        /** Default timeout in seconds for a single child subagent. Default 300. */
        private int defaultTimeoutSeconds = 300;
        /** Hard wall-clock cap in seconds for a single child (0 or negative = disabled). Default 0 (disabled). */
        private int childTimeoutSeconds = 0;
        /** Global kill switch for the orchestrator role. When false, role='orchestrator' is forced to 'leaf'. Default true. */
        private boolean orchestratorEnabled = true;
        /**
         * When true, skip the approval gate for delegated tasks (subagent auto-approve).
         * The child session carries a metadata flag that tells DefaultAgentRuntime to
         * bypass approval-queue checks for every tool call in that session.
         * Default false — approval is still required unless explicitly enabled.
         */
        private boolean subagentAutoApprove = false;
        /**
         * Maximum iterations (model calls) per child subagent turn.
         * Mirrors Hermes delegation.max_iterations (default 50).
         * When 0 or negative, the child uses the global core.max-turns setting.
         */
        private int maxIterations = 50;
        /**
         * Optional model name override for delegated subagents.
         * Mirrors Hermes delegation.model — routes subagents to a different model.
         */
        private String model = "";
        /**
         * Optional provider override for delegated subagents.
         * Mirrors Hermes delegation.provider — routes subagents to a different provider.
         */
        private String provider = "";
        /**
         * Optional reasoning effort override for delegated subagents.
         * Mirrors Hermes delegation.reasoning_effort.
         */
        private String reasoningEffort = "";
        /** Tools that children must never have access to (always stripped from child toolsets). */
        private final List<String> blockedTools = new ArrayList<>(java.util.List.of(
            "delegate_task",   // no recursive delegation (leaf children)
            "clarify",          // no user interaction
            "memory",           // no writes to shared MEMORY.md
            "send_message",     // no cross-platform side effects
            "execute_code"      // children should reason step-by-step, not write scripts
        ));

        public void setBlockedTools(List<String> tools) { this.blockedTools.clear(); this.blockedTools.addAll(tools); }
    }

    @Getter @Setter
    public static class McpProperties {
        private boolean enabled = false;
        /** Feature 4: OSV malware check before launching MCP servers (default true). */
        private boolean osvCheckEnabled = true;
        /** Max calls per window per tool (0 = no limit). */
        private int rateLimitMaxCalls = 0;
        /** Rate limit window in seconds (0 = no limit). */
        private long rateLimitWindowSeconds = 0;
        private final List<ServerProperties> servers = new ArrayList<>();
        private final Server server = new Server();

        @Getter @Setter
        public static class ServerProperties {
            private String name = "";
            private String transport = "stdio";
            private String command = "";
            private final List<String> args = new ArrayList<>();
            private final Map<String, String> env = new HashMap<>();
            private String baseUrl = "";
            private int timeoutSeconds = 30;
            // OAuth configuration for remote MCP servers
            private String oauthTokenUrl = "";
            private String oauthClientId = "";
            private String oauthClientSecret = "";
            /** OAuth scopes (space-separated), empty = use server defaults */
            private String oauthScopes = "";
        }

        /** MCP server mode: expose the agent's own tools to external MCP clients. */
        @Getter @Setter
        public static class Server {
            /** Whether the MCP server is enabled (default false). */
            private boolean enabled = false;
            /** Transport: "stdio" or "sse". */
            private String transport = "stdio";
            /** SSE endpoint path (default "/mcp/sse"). */
            private String sseEndpoint = "/mcp/sse";
            /** SSE message endpoint path (default "/mcp/message"). */
            private String messageEndpoint = "/mcp/message";
            /** Server name reported to MCP clients. */
            private String name = "java-agent";
            /** Server version reported to MCP clients. */
            private String version = "1.0.0";
        }
    }

    @Getter @Setter
    public static class SecurityProperties {
        private boolean approvalsEnabled = true;
        private boolean fileSafetyEnabled = true;
        private boolean urlSafetyEnabled = true;
        private boolean redactEnabled = true;
        /** Whether built-in secret patterns (API keys, tokens, etc.) are redacted (default true). */
        private boolean redactSecrets = true;
        /** Whether PII patterns (emails, phone numbers, etc.) are redacted (default false). */
        private boolean redactPii = false;
        /** REST API key for authenticating incoming HTTP requests (empty = auth disabled / dev mode). */
        private String apiKey = "";
        private final List<String> alwaysRequireApprovalTools = new ArrayList<>();
        private final List<String> sensitiveEnvVarPatterns = new ArrayList<>();
        private final List<String> allowedPaths = new ArrayList<>();
        private final List<String> blockedCommands = new ArrayList<>();
        private final List<String> blockedUrlHosts = new ArrayList<>();
        private final List<String> secretPatterns = new ArrayList<>();

        public void setAlwaysRequireApprovalTools(List<String> tools) { this.alwaysRequireApprovalTools.clear(); this.alwaysRequireApprovalTools.addAll(tools); }
        public void setAllowedPaths(List<String> allowedPaths) { this.allowedPaths.clear(); this.allowedPaths.addAll(allowedPaths); }
        public void setBlockedCommands(List<String> blockedCommands) { this.blockedCommands.clear(); this.blockedCommands.addAll(blockedCommands); }
        public void setBlockedUrlHosts(List<String> blockedUrlHosts) { this.blockedUrlHosts.clear(); this.blockedUrlHosts.addAll(blockedUrlHosts); }
        public void setSecretPatterns(List<String> secretPatterns) { this.secretPatterns.clear(); this.secretPatterns.addAll(secretPatterns); }
    }

    @Getter @Setter
    public static class GatewayProperties {
        private final TelegramProperties telegram = new TelegramProperties();
        /** Busy-input mode: "interrupt" (default), "queue", or "steer". */
        private String busyInputMode = "interrupt";
        /** Whether to send busy-ack messages when a user message arrives mid-run (default true). */
        private boolean busyAckEnabled = true;
    }

    @Getter @Setter
    public static class TelegramProperties {
        private String botToken = "";
        private String webhookUrl = "";
        private int timeoutSeconds = 30;
        private final List<String> allowedUserIds = new ArrayList<>();
        private final List<String> allowedUsernames = new ArrayList<>();
        private boolean allowByDefault = false;
    }

    @Getter @Setter
    public static class CoreProperties {
        private int maxTurns = 100;
        private String toolUseEnforcement = "auto";
        private boolean taskCompletionGuidance = true;
        private boolean parallelToolCallGuidance = true;
        private boolean autoTitleSession = true;
        private String reasoningConfig = "medium";
        private String defaultSystemPrompt = "You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested.";
        private int httpClientTimeoutSeconds = 30;
        private int maxReferenceFileBytes = 100_000;
        private String workingDirectory = System.getProperty("user.dir");
        /** Coding posture: auto, focus, on, off. Hermes parity: agent.coding_context. */
        private String codingContext = "auto";
        private String httpUserAgent = "AzhukovAgent/1.0";
        /** Finding 10.1: Configurable SOUL.md path (default: ~/.hermes/soul.md). */
        private String soulMdPath = "";
        // h63: Whether to retry on empty responses (default false — return immediately).
        private boolean emptyResponseRetry = false;
        /**
         * Hermes parity: jittered backoff base/cap for empty-response retries
         * (conversation_loop.py jittered_backoff base=5s max=60s). Overridable for tests.
         */
        private long emptyBackoffBaseMs = 5_000L;
        private long emptyBackoffCapMs = 60_000L;

        public int getMaxTotalChars() { return 64000; }
    }

    @Getter @Setter
    public static class BudgetProperties {
        private int maxModelCallsPerTurn = 100;
        private int maxToolExecutionsPerTurn = 200;
        private int maxTokensPerTurn = 200000;
        private int maxToolDurationMsPerTurn = 600000;
        private boolean enabled = true;
    }

    @Getter @Setter
    public static class PromptCachingProperties {
        private boolean enabled = true;
        private boolean trackStats = false;
    }

    @Getter @Setter
    public static class CheckpointProperties {
        private boolean enabled = true;
        private int maxSnapshots = 20;
        private int maxSizeMb = 500;
    }

    @Getter @Setter
    public static class VerifyOnStopProperties {
        /** Whether to nudge the model to verify after editing code. Default false (opt-in). */
        private boolean enabled = false;
    }

    @Getter @Setter
    public static class UsageProperties {
        private boolean trackEnabled = true;
        private boolean showCost = false;
        private boolean showTokenAnalytics = false;
    }

    @Getter @Setter
    public static class ImageGenProperties {
        private boolean enabled = false;
        private String provider = "fal";
        private String apiKey = "";
        private String model = "";
    }

    @Getter @Setter
    public static class TtsProperties {
        private boolean enabled = false;
        private String provider = "edge";
        private String apiKey = "";
        private String voice = "alloy";
        private boolean autoTts = false;
    }

    @Getter @Setter
    public static class TranscriptionProperties {
        private boolean enabled = false;
        private String provider = "openai";
        private String apiKey = "";
        private String model = "whisper-1";
    }

    @Getter @Setter
    public static class CronProperties {
        private boolean enabled = false;
        private int maxParallelJobs = 10;
        private int dispatchIntervalSeconds = 60;
        /** HERMES-SYNC: Consecutive failures before showing "needs attention" nudge (default 3). */
        private int nudgeFailureThreshold = 3;
    }

    @Getter @Setter
    public static class ErrorProperties {
        private int retryAttempts = 3;
        private int retryDelayMs = 1000;
        private int backoffMultiplier = 2;
        /** Cap for exponential backoff delay in milliseconds (default 60s). */
        private int retryCapMs = 60_000;
    }

    @Getter @Setter
    public static class CodingContextProperties {
        private boolean enabled = true;
        private double minScore = 0.5;
    }

    @Getter @Setter
    public static class ToolProperties {
        private boolean managedGatewayEnabled = false;
    }

    @Getter @Setter
    public static class CompressionProperties {
        private boolean enabled = true;
        private int summaryChunkTokens = 2000;
        private boolean abortOnSummaryFailure = false;
        /**
         * HERMES-SYNC Bug 4: Compression summary timeout in seconds (default 120s).
         * Acts as the IDLE budget in Hermes terms (DEFAULT_CONTEXT_TIMEOUT_SECONDS = 120.0,
         * conversation_compression.py:698): inactivity-based — streamed summary
         * progress extends the wait. Prevents hang on slow LLM.
         */
        private int summaryTimeoutSeconds = 120;
        /**
         * Hermes parity (DEFAULT_CONTEXT_TOTAL_CEILING_SECONDS = 600.0,
         * conversation_compression.py:699): hard ceiling bounding a degenerate
         * trickle stream. The summary phase is bounded by min(idle, ceiling) —
         * the ceiling only kicks in when idle > ceiling, but keeping it explicit
         * matches Hermes budgets and documents the upper bound.
         */
        private int totalCeilingSeconds = 600;
        private final SessionRotationProperties sessionRotation = new SessionRotationProperties();

        @Getter @Setter
        public static class SessionRotationProperties {
            /** Whether to create a child session after compression (default true). */
            private boolean enabled = true;
        }
    }

    // S5: Curator configuration — config-driven interval, idle gating, stale/archive thresholds
    @Getter @Setter
    public static class CuratorProperties {
        /** Whether the curator is enabled (default true). */
        private boolean enabled = true;
        /** Interval between curator runs in hours (default 7 days = 168h). */
        private int intervalHours = 24 * 7;
        /** Minimum idle hours before curator runs (default 2h). */
        private double minIdleHours = 2.0;
        /** Days of inactivity before a skill is marked stale (default 30). */
        private int staleAfterDays = 30;
        /** Days of inactivity before a stale skill is archived (default 90). */
        private int archiveAfterDays = 90;
        /** Whether the curator may prune bundled built-in skills (default true). */
        private boolean pruneBuiltins = true;
        /** Dry-run mode — report only, no mutations (default false). */
        private boolean dryRun = false;
        /** Number of backups to keep (default 5). */
        private int backupKeep = 5;
        /** Maximum iterations for the curator agent loop (default 10). */
        private int maxCuratorIterations = 10;
    }

    @Getter @Setter
    public static class ProfileProperties {
        /** Feature 10: Active profile name (default "default"). */
        private String name = "default";
        /** Feature 10: Base directory for profiles (default ~/.java-agent/profiles/). */
        private String baseDir = "";
    }
}