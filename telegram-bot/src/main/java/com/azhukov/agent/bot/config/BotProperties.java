package com.azhukov.agent.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    private String token = "";
    private String mode = "polling"; // polling | webhook
    private String agentName = "Джава агент";
    private String backendUrl = "http://localhost:8090";
    private int maxMessageLength = 4096;
    private Duration typingRefreshInterval = Duration.ofSeconds(4);
    private Duration streamEditInterval = Duration.ofMillis(1500);
    private String busyMode = "queue"; // queue | interrupt
    private String parseMode = "MarkdownV2"; // MarkdownV2 | HTML
    private boolean registerCommands = true;
    private int rateLimitPerSecond = 25;
    private String workingDirectory = System.getProperty("user.dir");
    private String defaultModel = "";
    private String replyToMode = "first"; // off | all | first

    private final Polling polling = new Polling();
    private final Webhook webhook = new Webhook();
    private final Auth auth = new Auth();
    private final Footer footer = new Footer();
    private final Reactions reactions = new Reactions();
    private final TextBatch textBatch = new TextBatch();
    private final Group group = new Group();

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getBackendUrl() { return backendUrl; }
    public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }
    public int getMaxMessageLength() { return maxMessageLength; }
    public void setMaxMessageLength(int maxMessageLength) { this.maxMessageLength = maxMessageLength; }
    public Duration getTypingRefreshInterval() { return typingRefreshInterval; }
    public void setTypingRefreshInterval(Duration typingRefreshInterval) { this.typingRefreshInterval = typingRefreshInterval; }
    public Duration getStreamEditInterval() { return streamEditInterval; }
    public void setStreamEditInterval(Duration streamEditInterval) { this.streamEditInterval = streamEditInterval; }
    public String getBusyMode() { return busyMode; }
    public void setBusyMode(String busyMode) { this.busyMode = busyMode; }
    public String getParseMode() { return parseMode; }
    public void setParseMode(String parseMode) { this.parseMode = parseMode; }
    public boolean isRegisterCommands() { return registerCommands; }
    public void setRegisterCommands(boolean registerCommands) { this.registerCommands = registerCommands; }
    public int getRateLimitPerSecond() { return rateLimitPerSecond; }
    public void setRateLimitPerSecond(int rateLimitPerSecond) { this.rateLimitPerSecond = rateLimitPerSecond; }
    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getReplyToMode() { return replyToMode; }
    public void setReplyToMode(String replyToMode) { this.replyToMode = replyToMode; }
    public Polling getPolling() { return polling; }
    public Webhook getWebhook() { return webhook; }
    public Auth getAuth() { return auth; }
    public Footer getFooter() { return footer; }
    public Reactions getReactions() { return reactions; }
    public TextBatch getTextBatch() { return textBatch; }
    public Group getGroup() { return group; }

    public static class Polling {
        private int timeoutSeconds = 30;
        private int limit = 100;
        private long reconnectDelayMs = 5000;
        private double reconnectBackoffMultiplier = 1.5;
        private long reconnectMaxDelayMs = 60000;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public long getReconnectDelayMs() { return reconnectDelayMs; }
        public void setReconnectDelayMs(long reconnectDelayMs) { this.reconnectDelayMs = reconnectDelayMs; }
        public double getReconnectBackoffMultiplier() { return reconnectBackoffMultiplier; }
        public void setReconnectBackoffMultiplier(double reconnectBackoffMultiplier) { this.reconnectBackoffMultiplier = reconnectBackoffMultiplier; }
        public long getReconnectMaxDelayMs() { return reconnectMaxDelayMs; }
        public void setReconnectMaxDelayMs(long reconnectMaxDelayMs) { this.reconnectMaxDelayMs = reconnectMaxDelayMs; }
    }

    public static class Webhook {
        private String url = "";
        private String secret = "";
        private String path = "/webhook/telegram";
        private int port = 8443;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Auth {
        private final List<String> allowedUserIds = new ArrayList<>();
        private final List<String> allowedUsernames = new ArrayList<>();
        private final List<String> allowedChatIds = new ArrayList<>();
        private boolean allowByDefault = false;
        private final List<String> adminUserIds = new ArrayList<>();
        private final List<String> userAllowedCommands = new ArrayList<>();

        public List<String> getAllowedUserIds() { return allowedUserIds; }
        public List<String> getAllowedUsernames() { return allowedUsernames; }
        public List<String> getAllowedChatIds() { return allowedChatIds; }
        public boolean isAllowByDefault() { return allowByDefault; }
        public void setAllowByDefault(boolean allowByDefault) { this.allowByDefault = allowByDefault; }
        public List<String> getAdminUserIds() { return adminUserIds; }
        public List<String> getUserAllowedCommands() { return userAllowedCommands; }
    }

    public static class Footer {
        private boolean enabled = false;
        private final List<String> fields = new ArrayList<>(List.of("model", "context_pct", "cwd"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getFields() { return fields; }
    }

    public static class Reactions {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class TextBatch {
        private int delayMs = 500;
        private int splitDelayMs = 1200;
        private int fastDelayMs = 180;

        public int getDelayMs() { return delayMs; }
        public void setDelayMs(int delayMs) { this.delayMs = delayMs; }
        public int getSplitDelayMs() { return splitDelayMs; }
        public void setSplitDelayMs(int splitDelayMs) { this.splitDelayMs = splitDelayMs; }
        public int getFastDelayMs() { return fastDelayMs; }
        public void setFastDelayMs(int fastDelayMs) { this.fastDelayMs = fastDelayMs; }
    }

    public static class Group {
        private boolean requireMention = false;
        private boolean guestMode = false;
        private boolean observeUnmentioned = false;
        private boolean exclusiveBotMentions = false;
        private final List<String> freeResponseChats = new ArrayList<>();
        private final List<String> allowedTopics = new ArrayList<>();
        private final List<Long> ignoredThreads = new ArrayList<>();

        public boolean isRequireMention() { return requireMention; }
        public void setRequireMention(boolean requireMention) { this.requireMention = requireMention; }
        public boolean isGuestMode() { return guestMode; }
        public void setGuestMode(boolean guestMode) { this.guestMode = guestMode; }
        public boolean isObserveUnmentioned() { return observeUnmentioned; }
        public void setObserveUnmentioned(boolean observeUnmentioned) { this.observeUnmentioned = observeUnmentioned; }
        public boolean isExclusiveBotMentions() { return exclusiveBotMentions; }
        public void setExclusiveBotMentions(boolean exclusiveBotMentions) { this.exclusiveBotMentions = exclusiveBotMentions; }
        public List<String> getFreeResponseChats() { return freeResponseChats; }
        public List<String> getAllowedTopics() { return allowedTopics; }
        public List<Long> getIgnoredThreads() { return ignoredThreads; }
    }
}