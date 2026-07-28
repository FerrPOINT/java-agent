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
    private final UsageProperties usage = new UsageProperties();
    private final ImageGenProperties imageGen = new ImageGenProperties();
    private final TtsProperties tts = new TtsProperties();
    private final TranscriptionProperties transcription = new TranscriptionProperties();
    private final CronProperties cron = new CronProperties();
    private final StreamingProperties streaming = new StreamingProperties();
    private final ErrorProperties error = new ErrorProperties();
    private final CodingContextProperties codingContext = new CodingContextProperties();
    private final ToolProperties tools = new ToolProperties();
    private final CompressionProperties compression = new CompressionProperties();

    @Getter @Setter
    public static class ModelProperties {
        private String provider = "openai-compatible";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 600;
        private int maxRetries = 3;
        private int maxTokens = 4096;
        private double temperature = 0.7;
        private final Map<String, String> headers = new HashMap<>();
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
        private final BackgroundReviewProperties backgroundReview = new BackgroundReviewProperties();
    }

    @Getter @Setter
    public static class BackgroundReviewProperties {
        private boolean enabled = true;
        private int delayMs = 2000;
    }

    @Getter @Setter
    public static class SkillsProperties {
        private boolean enabled = true;
        private int maxSkillsInPrompt = 20;
        private int maxCharsPerSkill = 4000;
        private final List<String> defaultToolsets = new ArrayList<>(List.of("web", "file", "browser", "terminal", "coding", "memory", "skills", "core", "delegate"));
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

        public int getTimeoutSecondsOrDefault(int fallback) { return timeoutSeconds > 0 ? timeoutSeconds : fallback; }
    }

    @Getter @Setter
    public static class ContextProperties {
        private int maxTokens = 16000;
        private int targetTokens = 12000;
        private int summaryChunkTokens = 2000;
        private int maxContextMessages = 50;
    }

    @Getter @Setter
    public static class DelegationProperties {
        private boolean enabled = true;
        private int maxDepth = 3;
        private int defaultTimeoutSeconds = 300;
    }

    @Getter @Setter
    public static class McpProperties {
        private boolean enabled = false;
        private final List<ServerProperties> servers = new ArrayList<>();

        @Getter @Setter
        public static class ServerProperties {
            private String name = "";
            private String transport = "stdio";
            private String command = "";
            private final List<String> args = new ArrayList<>();
            private final Map<String, String> env = new HashMap<>();
            private String baseUrl = "";
            private int timeoutSeconds = 30;
        }
    }

    @Getter @Setter
    public static class SecurityProperties {
        private boolean approvalsEnabled = true;
        private boolean fileSafetyEnabled = true;
        private boolean urlSafetyEnabled = true;
        private boolean redactEnabled = true;
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
        private int maxTurns = 90;
        private String toolUseEnforcement = "auto";
        private boolean taskCompletionGuidance = true;
        private boolean parallelToolCallGuidance = true;
        private boolean autoTitleSession = true;
        private String reasoningConfig = "medium";
        private String defaultSystemPrompt = "You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested.";
        private int httpClientTimeoutSeconds = 30;
        private int maxReferenceFileBytes = 100_000;
        private String workingDirectory = System.getProperty("user.dir");
        private String httpUserAgent = "AzhukovAgent/1.0";

        public int getMaxTotalChars() { return 64000; }
    }

    @Getter @Setter
    public static class BudgetProperties {
        private int maxModelCallsPerTurn = 5;
        private int maxToolExecutionsPerTurn = 20;
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
    }

    @Getter @Setter
    public static class StreamingProperties {
        private boolean scrubThinkBlocks = true;
        private int editIntervalMs = 1500;
    }

    @Getter @Setter
    public static class ErrorProperties {
        private int retryAttempts = 3;
        private int retryDelayMs = 1000;
        private int backoffMultiplier = 2;
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
    }
}