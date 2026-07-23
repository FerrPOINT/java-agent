package com.azhukov.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String name = "Джава агент";
    private ModelProperties model = new ModelProperties();
    private VisionProperties vision = new VisionProperties();
    private BrowserProperties browser = new BrowserProperties();
    private MemoryProperties memory = new MemoryProperties();
    private SkillsProperties skills = new SkillsProperties();

    private CoreProperties core = new CoreProperties();

    // getters / setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CoreProperties getCore() { return core; }
    public void setCore(CoreProperties core) { this.core = core; }
    public ModelProperties getModel() { return model; }
    public void setModel(ModelProperties model) { this.model = model; }
    public VisionProperties getVision() { return vision; }
    public void setVision(VisionProperties vision) { this.vision = vision; }
    public BrowserProperties getBrowser() { return browser; }
    public void setBrowser(BrowserProperties browser) { this.browser = browser; }
    public MemoryProperties getMemory() { return memory; }
    public void setMemory(MemoryProperties memory) { this.memory = memory; }
    public SkillsProperties getSkills() { return skills; }
    public void setSkills(SkillsProperties skills) { this.skills = skills; }
}

class ModelProperties {
    private String provider = "openai";
    private String baseUrl;
    private String apiKey;
    private String modelName = "gpt-4o-mini";
    private double temperature = 0.7;
    private int maxTokens = 4096;
    private long timeoutSeconds = 60;
    private int maxRetries = 3;

    // getters / setters
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}

class VisionProperties {
    private String provider = "kimi";
    private String baseUrl = "https://api.moonshot.ai/v1";
    private String apiKey;
    private String modelName = "kimi-k2.7-code";
    private long maxDownloadBytes = 50L * 1024 * 1024;
    private long downloadTimeoutSeconds = 30;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public long getMaxDownloadBytes() { return maxDownloadBytes; }
    public void setMaxDownloadBytes(long maxDownloadBytes) { this.maxDownloadBytes = maxDownloadBytes; }
    public long getDownloadTimeoutSeconds() { return downloadTimeoutSeconds; }
    public void setDownloadTimeoutSeconds(long downloadTimeoutSeconds) { this.downloadTimeoutSeconds = downloadTimeoutSeconds; }
}

class BrowserProperties {
    private String executable = "google-chrome";
    private boolean headless = true;
    private List<String> args = List.of("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage");
    private String cdpUrl;
    private long timeoutSeconds = 30;

    public String getExecutable() { return executable; }
    public void setExecutable(String executable) { this.executable = executable; }
    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }
    public String getCdpUrl() { return cdpUrl; }
    public void setCdpUrl(String cdpUrl) { this.cdpUrl = cdpUrl; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}

class MemoryProperties {
    private boolean enabled = true;
    private String provider = "sqlite";
    private int maxContextChars = 6000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getMaxContextChars() { return maxContextChars; }
    public void setMaxContextChars(int maxContextChars) { this.maxContextChars = maxContextChars; }
}

class SkillsProperties {
    private boolean enabled = true;
    private String directory = "~/.java-agent/skills";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }
}

class CoreProperties {
    private String defaultSystemPrompt = "You are Джава агент. Use available tools when needed. Be concise.";

    public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
    public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
}
