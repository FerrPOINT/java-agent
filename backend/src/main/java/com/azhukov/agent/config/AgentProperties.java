package com.azhukov.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "agent")
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ModelProperties getModel() { return model; }
    public AuxiliaryProperties getAuxiliary() { return auxiliary; }
    public VisionProperties getVision() { return vision; }
    public BrowserProperties getBrowser() { return browser; }
    public ChromiumProperties getChromium() { return chromium; }
    public WebProperties getWeb() { return web; }
    public TerminalProperties getTerminal() { return terminal; }
    public FileProperties getFile() { return file; }
    public MemoryProperties getMemory() { return memory; }
    public SkillsProperties getSkills() { return skills; }
    public SessionSearchProperties getSessionSearch() { return sessionSearch; }
    public ToolOutputProperties getToolOutput() { return toolOutput; }
    public ContextProperties getContext() { return context; }
    public DelegationProperties getDelegation() { return delegation; }
    public McpProperties getMcp() { return mcp; }
    public GatewayProperties getGateway() { return gateway; }
    public SecurityProperties getSecurity() { return security; }
    public CoreProperties getCore() { return core; }
    public BudgetProperties getBudget() { return budget; }

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

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public Map<String, String> getHeaders() { return headers; }
    }

    public static class AuxiliaryProperties {
        private boolean enabled = false;
        private String provider = "openai-compatible";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 600;
        private int maxRetries = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class VisionProperties {
        private String provider = "";
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
        private int timeoutSeconds = 600;
        private int maxRetries = 3;
        private boolean useAuxiliaryFirst = true;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public boolean isUseAuxiliaryFirst() { return useAuxiliaryFirst; }
        public void setUseAuxiliaryFirst(boolean useAuxiliaryFirst) { this.useAuxiliaryFirst = useAuxiliaryFirst; }
    }

    public static class BrowserProperties {
        private String cdpUrl = "http://localhost:9222";
        private int defaultTimeoutMs = 120000;
        private int pageLoadTimeoutMs = 120000;
        private int maxTabs = 5;
        private boolean headless = true;
        private String executablePath = "";

        public String getCdpUrl() { return cdpUrl; }
        public void setCdpUrl(String cdpUrl) { this.cdpUrl = cdpUrl; }
        public int getDefaultTimeoutMs() { return defaultTimeoutMs; }
        public void setDefaultTimeoutMs(int defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
        public int getPageLoadTimeoutMs() { return pageLoadTimeoutMs; }
        public void setPageLoadTimeoutMs(int pageLoadTimeoutMs) { this.pageLoadTimeoutMs = pageLoadTimeoutMs; }
        public int getMaxTabs() { return maxTabs; }
        public void setMaxTabs(int maxTabs) { this.maxTabs = maxTabs; }
        public boolean isHeadless() { return headless; }
        public void setHeadless(boolean headless) { this.headless = headless; }
        public String getExecutablePath() { return executablePath; }
        public void setExecutablePath(String executablePath) { this.executablePath = executablePath; }
    }

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

        public boolean isAutoStart() { return autoStart; }
        public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
        public boolean isAutoInstall() { return autoInstall; }
        public void setAutoInstall(boolean autoInstall) { this.autoInstall = autoInstall; }
        public String getDownloadUrl() { return downloadUrl; }
        public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }
        public String getRevision() { return revision; }
        public void setRevision(String revision) { this.revision = revision; }
        public int getLaunchTimeoutSeconds() { return launchTimeoutSeconds; }
        public void setLaunchTimeoutSeconds(int launchTimeoutSeconds) { this.launchTimeoutSeconds = launchTimeoutSeconds; }
        public boolean isHeadless() { return headless; }
        public void setHeadless(boolean headless) { this.headless = headless; }
        public String getExecutablePath() { return executablePath; }
        public void setExecutablePath(String executablePath) { this.executablePath = executablePath; }
        public String getUserDataDir() { return userDataDir; }
        public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }
        public List<String> getExtraArgs() { return extraArgs; }
    }

    public static class WebProperties {
        private int searchResults = 5;
        private int extractTimeoutSeconds = 120;
        private int extractMaxChars = 100000;
        private String searchProvider = "ddg";
        private final List<String> allowedDomains = new ArrayList<>();
        private final List<String> blockedDomains = new ArrayList<>();

        public int getSearchResults() { return searchResults; }
        public void setSearchResults(int searchResults) { this.searchResults = searchResults; }
        public int getExtractTimeoutSeconds() { return extractTimeoutSeconds; }
        public void setExtractTimeoutSeconds(int extractTimeoutSeconds) { this.extractTimeoutSeconds = extractTimeoutSeconds; }
        public int getExtractMaxChars() { return extractMaxChars; }
        public void setExtractMaxChars(int extractMaxChars) { this.extractMaxChars = extractMaxChars; }
        public String getSearchProvider() { return searchProvider; }
        public void setSearchProvider(String searchProvider) { this.searchProvider = searchProvider; }
        public List<String> getAllowedDomains() { return allowedDomains; }
        public List<String> getBlockedDomains() { return blockedDomains; }
    }

    public static class TerminalProperties {
        @jakarta.validation.constraints.Positive
        private int defaultTimeoutSeconds = 300;
        @jakarta.validation.constraints.Positive
        private int maxTimeoutSeconds = 1800;
        private boolean dockerEnabled = false;
        private final List<String> blockedCommands = new ArrayList<>();
        private final List<String> requireApprovalCommands = new ArrayList<>();

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
        public boolean isDockerEnabled() { return dockerEnabled; }
        public void setDockerEnabled(boolean dockerEnabled) { this.dockerEnabled = dockerEnabled; }
        public List<String> getBlockedCommands() { return blockedCommands; }
        public List<String> getRequireApprovalCommands() { return requireApprovalCommands; }
    }

    public static class FileProperties {
        private int readMaxChars = 100000;
        private int writeMaxChars = 100000;
        private final List<String> allowedPaths = new ArrayList<>();
        private final List<String> blockedPaths = new ArrayList<>();
        private final List<String> blockedExtensions = new ArrayList<>();

        public int getReadMaxChars() { return readMaxChars; }
        public void setReadMaxChars(int readMaxChars) { this.readMaxChars = readMaxChars; }
        public int getWriteMaxChars() { return writeMaxChars; }
        public void setWriteMaxChars(int writeMaxChars) { this.writeMaxChars = writeMaxChars; }
        public List<String> getAllowedPaths() { return allowedPaths; }
        public List<String> getBlockedPaths() { return blockedPaths; }
        public List<String> getBlockedExtensions() { return blockedExtensions; }
    }

    public static class MemoryProperties {
        private int maxFactsPerUser = 1000;
        private int maxFactsPerQuery = 10;
        private double similarityThreshold = 0.75;

        public int getMaxFactsPerUser() { return maxFactsPerUser; }
        public void setMaxFactsPerUser(int maxFactsPerUser) { this.maxFactsPerUser = maxFactsPerUser; }
        public int getMaxFactsPerQuery() { return maxFactsPerQuery; }
        public void setMaxFactsPerQuery(int maxFactsPerQuery) { this.maxFactsPerQuery = maxFactsPerQuery; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    }

    public static class SkillsProperties {
        private boolean enabled = true;
        private int maxSkillsInPrompt = 20;
        private int maxCharsPerSkill = 4000;
        private final List<String> defaultToolsets = new ArrayList<>(List.of("web", "file", "browser", "terminal", "coding", "memory", "skills", "core", "delegate"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxSkillsInPrompt() { return maxSkillsInPrompt; }
        public void setMaxSkillsInPrompt(int maxSkillsInPrompt) { this.maxSkillsInPrompt = maxSkillsInPrompt; }
        public int getMaxCharsPerSkill() { return maxCharsPerSkill; }
        public void setMaxCharsPerSkill(int maxCharsPerSkill) { this.maxCharsPerSkill = maxCharsPerSkill; }
        public List<String> getDefaultToolsets() { return defaultToolsets; }
    }

    public static class SessionSearchProperties {
        private int maxResults = 10;
        private int snippetChars = 200;

        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getSnippetChars() { return snippetChars; }
        public void setSnippetChars(int snippetChars) { this.snippetChars = snippetChars; }
    }

    public static class ToolOutputProperties {
        private int maxChars = 16000;
        private int truncateWarningChars = 12000;
        private int timeoutSeconds = 300;
        private boolean includeTimestamps = true;

        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getTruncateWarningChars() { return truncateWarningChars; }
        public void setTruncateWarningChars(int truncateWarningChars) { this.truncateWarningChars = truncateWarningChars; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getTimeoutSecondsOrDefault(int fallback) { return timeoutSeconds > 0 ? timeoutSeconds : fallback; }
        public boolean isIncludeTimestamps() { return includeTimestamps; }
        public void setIncludeTimestamps(boolean includeTimestamps) { this.includeTimestamps = includeTimestamps; }
    }

    public static class ContextProperties {
        private int maxTokens = 16000;
        private int targetTokens = 12000;
        private int summaryChunkTokens = 2000;
        private int maxContextMessages = 50;

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getTargetTokens() { return targetTokens; }
        public void setTargetTokens(int targetTokens) { this.targetTokens = targetTokens; }
        public int getSummaryChunkTokens() { return summaryChunkTokens; }
        public void setSummaryChunkTokens(int summaryChunkTokens) { this.summaryChunkTokens = summaryChunkTokens; }
        public int getMaxContextMessages() { return maxContextMessages; }
        public void setMaxContextMessages(int maxContextMessages) { this.maxContextMessages = maxContextMessages; }
    }

    public static class DelegationProperties {
        private boolean enabled = true;
        private int maxDepth = 3;
        private int defaultTimeoutSeconds = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }
    }

    public static class McpProperties {
        private boolean enabled = false;
        private final List<ServerProperties> servers = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<ServerProperties> getServers() { return servers; }

        public static class ServerProperties {
            private String name = "";
            private String transport = "stdio";
            private String command = "";
            private final List<String> args = new ArrayList<>();
            private final Map<String, String> env = new HashMap<>();
            private String baseUrl = "";
            private int timeoutSeconds = 30;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getTransport() { return transport; }
            public void setTransport(String transport) { this.transport = transport; }
            public String getCommand() { return command; }
            public void setCommand(String command) { this.command = command; }
            public List<String> getArgs() { return args; }
            public Map<String, String> getEnv() { return env; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        }
    }

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

        public boolean isApprovalsEnabled() { return approvalsEnabled; }
        public void setApprovalsEnabled(boolean approvalsEnabled) { this.approvalsEnabled = approvalsEnabled; }
        public boolean isFileSafetyEnabled() { return fileSafetyEnabled; }
        public void setFileSafetyEnabled(boolean fileSafetyEnabled) { this.fileSafetyEnabled = fileSafetyEnabled; }
        public boolean isUrlSafetyEnabled() { return urlSafetyEnabled; }
        public void setUrlSafetyEnabled(boolean urlSafetyEnabled) { this.urlSafetyEnabled = urlSafetyEnabled; }
        public boolean isRedactEnabled() { return redactEnabled; }
        public void setRedactEnabled(boolean redactEnabled) { this.redactEnabled = redactEnabled; }
        public List<String> getAlwaysRequireApprovalTools() { return alwaysRequireApprovalTools; }
        public List<String> getSensitiveEnvVarPatterns() { return sensitiveEnvVarPatterns; }
        public List<String> getAllowedPaths() { return allowedPaths; }
        public void setAllowedPaths(List<String> allowedPaths) { this.allowedPaths.clear(); this.allowedPaths.addAll(allowedPaths); }
        public List<String> getBlockedCommands() { return blockedCommands; }
        public void setBlockedCommands(List<String> blockedCommands) { this.blockedCommands.clear(); this.blockedCommands.addAll(blockedCommands); }
        public List<String> getBlockedUrlHosts() { return blockedUrlHosts; }
        public void setBlockedUrlHosts(List<String> blockedUrlHosts) { this.blockedUrlHosts.clear(); this.blockedUrlHosts.addAll(blockedUrlHosts); }
        public List<String> getSecretPatterns() { return secretPatterns; }
        public void setSecretPatterns(List<String> secretPatterns) { this.secretPatterns.clear(); this.secretPatterns.addAll(secretPatterns); }
    }

    public static class GatewayProperties {
        private final TelegramProperties telegram = new TelegramProperties();

        public TelegramProperties getTelegram() { return telegram; }
    }

    public static class TelegramProperties {
        private String botToken = "";
        private String webhookUrl = "";
        private int timeoutSeconds = 30;
        private final List<String> allowedUserIds = new ArrayList<>();
        private final List<String> allowedUsernames = new ArrayList<>();
        private boolean allowByDefault = false;

        public String getBotToken() { return botToken; }
        public void setBotToken(String botToken) { this.botToken = botToken; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public List<String> getAllowedUserIds() { return allowedUserIds; }
        public List<String> getAllowedUsernames() { return allowedUsernames; }
        public boolean isAllowByDefault() { return allowByDefault; }
        public void setAllowByDefault(boolean allowByDefault) { this.allowByDefault = allowByDefault; }
    }

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

        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public String getToolUseEnforcement() { return toolUseEnforcement; }
        public void setToolUseEnforcement(String toolUseEnforcement) { this.toolUseEnforcement = toolUseEnforcement; }
        public boolean isTaskCompletionGuidance() { return taskCompletionGuidance; }
        public void setTaskCompletionGuidance(boolean taskCompletionGuidance) { this.taskCompletionGuidance = taskCompletionGuidance; }
        public boolean isParallelToolCallGuidance() { return parallelToolCallGuidance; }
        public void setParallelToolCallGuidance(boolean parallelToolCallGuidance) { this.parallelToolCallGuidance = parallelToolCallGuidance; }
        public boolean isAutoTitleSession() { return autoTitleSession; }
        public void setAutoTitleSession(boolean autoTitleSession) { this.autoTitleSession = autoTitleSession; }
        public String getReasoningConfig() { return reasoningConfig; }
        public void setReasoningConfig(String reasoningConfig) { this.reasoningConfig = reasoningConfig; }
        public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
        public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
        public int getHttpClientTimeoutSeconds() { return httpClientTimeoutSeconds; }
        public void setHttpClientTimeoutSeconds(int httpClientTimeoutSeconds) { this.httpClientTimeoutSeconds = httpClientTimeoutSeconds; }
        public int getMaxReferenceFileBytes() { return maxReferenceFileBytes; }
        public void setMaxReferenceFileBytes(int maxReferenceFileBytes) { this.maxReferenceFileBytes = maxReferenceFileBytes; }
        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
        public String getHttpUserAgent() { return httpUserAgent; }
        public void setHttpUserAgent(String httpUserAgent) { this.httpUserAgent = httpUserAgent; }
        public int getMaxTotalChars() { return 64000; }
    }

    public static class BudgetProperties {
        private int maxModelCallsPerTurn = 5;
        private int maxToolExecutionsPerTurn = 20;
        private int maxTokensPerTurn = 200000;
        private int maxToolDurationMsPerTurn = 600000;
        private boolean enabled = true;

        public int getMaxModelCallsPerTurn() { return maxModelCallsPerTurn; }
        public void setMaxModelCallsPerTurn(int maxModelCallsPerTurn) { this.maxModelCallsPerTurn = maxModelCallsPerTurn; }
        public int getMaxToolExecutionsPerTurn() { return maxToolExecutionsPerTurn; }
        public void setMaxToolExecutionsPerTurn(int maxToolExecutionsPerTurn) { this.maxToolExecutionsPerTurn = maxToolExecutionsPerTurn; }
        public int getMaxTokensPerTurn() { return maxTokensPerTurn; }
        public void setMaxTokensPerTurn(int maxTokensPerTurn) { this.maxTokensPerTurn = maxTokensPerTurn; }
        public int getMaxToolDurationMsPerTurn() { return maxToolDurationMsPerTurn; }
        public void setMaxToolDurationMsPerTurn(int maxToolDurationMsPerTurn) { this.maxToolDurationMsPerTurn = maxToolDurationMsPerTurn; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
