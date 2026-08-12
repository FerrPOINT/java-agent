package com.azhukov.agent.bot.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    private String token = "";
    private String mode = "polling"; // polling | webhook
    private String agentName = "Джава агент";
    private String backendUrl = "http://localhost:8090";
    @Min(1)
    private int maxMessageLength = 4096;
    private Duration typingRefreshInterval = Duration.ofSeconds(4);
    private Duration streamEditInterval = Duration.ofMillis(800);
    // Buffer threshold: when accumulated text since last edit reaches this many chars,
    // trigger an edit even if the edit interval hasn't elapsed yet.
    @Min(1)
    private int bufferThreshold = 24;
    private String busyMode = "queue"; // queue | interrupt
    private String parseMode = "MarkdownV2"; // MarkdownV2 | HTML
    private boolean registerCommands = true;
    @Min(1)
    private int rateLimitPerSecond = 25;
    private String workingDirectory = System.getProperty("user.dir");
    private String defaultModel = "";
    private final List<String> availableModels = new ArrayList<>();
    private String replyToMode = "first"; // off | all | first
    private String homeChatId = ""; // set by /set_home
    private boolean linkPreview = true; // B3.7: include link previews in sent messages

    // P0: When true, new bot instance can take over from an existing instance (--replace)
    private boolean replaceOnStart = false;

    // P0: PII Redaction — hash user IDs and chat IDs before injecting into system prompt
    private boolean redactPii = true;

    // P0: B7: When true, streaming edit messages are delivered silently (disable_notification=true).
    // Only the final message after streaming completes triggers a push notification.
    private boolean streamingSilent = true;

    // Streaming cursor: appended to text during editStream, stripped on finalize.
    private String streamCursor = " ▉";
    // Heartbeat interval: seconds between heartbeat checks during streaming.
    private int heartbeatIntervalSeconds = 180;
    // Fresh-final timeout: if streaming exceeds this (ms), delete old msg and send a new one.
    private long freshFinalTimeoutMs = 60000;
    // Initial streaming text shown before the first token arrives.
    // Hermes shows nothing until >=4 chars accumulated.
    private String initialStreamText = "";
    // Threshold (chars) to split streaming text into a new message during editStream.
    // Matches Hermes behavior when rich messages are available.
    private int streamingMaxChars = 32768;

    private final Polling polling = new Polling();
    private final Webhook webhook = new Webhook();
    private final Auth auth = new Auth();
    private final Footer footer = new Footer();
    private final Reactions reactions = new Reactions();
    private final TextBatch textBatch = new TextBatch();
    private final Group group = new Group();
    private final Display display = new Display();
    private final RichMessages richMessages = new RichMessages();
    private final SessionReset sessionReset = new SessionReset();
    private final GoalAutoContinue goalAutoContinue = new GoalAutoContinue();

    @Getter
    @Setter
    public static class GoalAutoContinue {
        private boolean enabled = false;
        @Min(0)
        private int maxTurns = 3;
    }

    @Getter
    @Setter
    public static class SessionReset {
        private String mode = "both"; // daily | idle | both | none
        private int atHour = 4;
        private int idleMinutes = 1440;
        private boolean notify = true;
    }

    @Getter
    @Setter
    public static class Polling {
        private int timeoutSeconds = 30;
        private int limit = 100;
        private long reconnectDelayMs = 5000;
        private double reconnectBackoffMultiplier = 1.5;
        private long reconnectMaxDelayMs = 60000;
        /** Max retries on HTTP 409 conflict (another polling instance) before stopping. */
        private int conflictMaxRetries = 5;
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
        private Long threadId = null;
        private Integer iconColor = null;
        private String iconCustomEmojiId = null;
        private String skill = null;
    }

    @Getter
    @Setter
    public static class RichMessages {
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Display {
        private String toolProgress = "hidden"; // compact | verbose | hidden
        private int previewLength = 200;
    }
}