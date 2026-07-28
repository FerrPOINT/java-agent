package com.azhukov.agent.bot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
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
    private String homeChatId = ""; // set by /set_home
    private boolean linkPreview = true; // B3.7: include link previews in sent messages

    private final Polling polling = new Polling();
    private final Webhook webhook = new Webhook();
    private final Auth auth = new Auth();
    private final Footer footer = new Footer();
    private final Reactions reactions = new Reactions();
    private final TextBatch textBatch = new TextBatch();
    private final Group group = new Group();
    private final Display display = new Display();

    @Getter
    @Setter
    public static class Polling {
        private int timeoutSeconds = 30;
        private int limit = 100;
        private long reconnectDelayMs = 5000;
        private double reconnectBackoffMultiplier = 1.5;
        private long reconnectMaxDelayMs = 60000;
    }

    @Getter
    @Setter
    public static class Webhook {
        private String url = "";
        private String secret = "";
        private String path = "/webhook/telegram";
        private int port = 8443;
    }

    @Getter
    @Setter
    public static class Auth {
        private final List<String> allowedUserIds = new ArrayList<>();
        private final List<String> allowedUsernames = new ArrayList<>();
        private final List<String> allowedChatIds = new ArrayList<>();
        private boolean allowByDefault = false;
        private final List<String> adminUserIds = new ArrayList<>();
        private final List<String> userAllowedCommands = new ArrayList<>();
        private final Pairing pairing = new Pairing();
    }

    @Getter
    @Setter
    public static class Pairing {
        private boolean enabled = false;
        private int codeExpiryHours = 1;
        private int maxPending = 3;
    }

    @Getter
    @Setter
    public static class Footer {
        private boolean enabled = false;
        private final List<String> fields = new ArrayList<>(List.of("model", "context_pct", "cwd"));
    }

    @Getter
    @Setter
    public static class Reactions {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class TextBatch {
        private int delayMs = 500;
        private int splitDelayMs = 1200;
        private int fastDelayMs = 180;
    }

    @Getter
    @Setter
    public static class Group {
        private boolean requireMention = false;
        private boolean guestMode = false;
        private boolean observeUnmentioned = false;
        private boolean exclusiveBotMentions = false;
        private final List<String> freeResponseChats = new ArrayList<>();
        private final List<String> allowedTopics = new ArrayList<>();
        private final List<Long> ignoredThreads = new ArrayList<>();
        private final List<DmTopic> dmTopics = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class DmTopic {
        private String chatId = "";
        private String topicName = "";
    }

    @Getter
    @Setter
    public static class Display {
        private String toolProgress = "compact"; // compact | verbose | hidden
        private int previewLength = 200;
    }
}