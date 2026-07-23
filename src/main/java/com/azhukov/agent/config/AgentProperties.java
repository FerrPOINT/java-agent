package com.azhukov.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String name = "Джава агент";
    private final ModelProperties model = new ModelProperties();
    private final VisionProperties vision = new VisionProperties();
    private final BrowserProperties browser = new BrowserProperties();
    private final CoreProperties core = new CoreProperties();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ModelProperties getModel() { return model; }
    public VisionProperties getVision() { return vision; }
    public BrowserProperties getBrowser() { return browser; }
    public CoreProperties getCore() { return core; }

    public static class ModelProperties {
        private String provider = "ollama";
        private String baseUrl = "http://localhost:11434";
        private String apiKey = "";
        private String modelName = "qwen2.5:3b";
        private String chatFormat = "chat";
        private int timeoutSeconds = 60;
        private int maxRetries = 3;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public String getChatFormat() { return chatFormat; }
        public void setChatFormat(String chatFormat) { this.chatFormat = chatFormat; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class VisionProperties {
        private String baseUrl = "http://localhost:11434";
        private String apiKey = "";
        private String modelName = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class BrowserProperties {
        private String cdpUrl = "http://localhost:9222";
        private int defaultTimeoutMs = 30000;

        public String getCdpUrl() { return cdpUrl; }
        public void setCdpUrl(String cdpUrl) { this.cdpUrl = cdpUrl; }
        public int getDefaultTimeoutMs() { return defaultTimeoutMs; }
        public void setDefaultTimeoutMs(int defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    }

    public static class CoreProperties {
        private String defaultSystemPrompt = "You are ${agent.name}. Use available tools when needed. Be concise. Return plain text unless JSON is requested.";

        public String getDefaultSystemPrompt() { return defaultSystemPrompt; }
        public void setDefaultSystemPrompt(String defaultSystemPrompt) { this.defaultSystemPrompt = defaultSystemPrompt; }
    }
}
