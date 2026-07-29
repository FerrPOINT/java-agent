package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BotLifecycleManager {

    private final TelegramClient telegramClient;
    private final BotProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.warn("Bot token is empty, skipping lifecycle setup");
            return;
        }

        if (properties.isRegisterCommands()) {
            registerCommands();
            registerCommandsForForums();
        }

        if ("webhook".equals(properties.getMode())) {
            setupWebhook();
        }
        // For polling mode, LongPollingService handles its own startup
    }

    private void registerCommands() {
        List<Map<String, String>> commands = buildCommandList();

        boolean ok = telegramClient.setMyCommands(commands);
        if (ok) {
            log.info("Registered {} bot commands (global scope)", commands.size());
        } else {
            log.warn("Failed to register bot commands");
        }
    }

    /**
     * B2.7: Register commands scoped to specific forum chats.
     * Uses {@code bot.group.allowed-topics} config — each entry is a numeric chat_id
     * of a forum where the bot should register commands with {@code BotCommandScopeChat}.
     */
    private void registerCommandsForForums() {
        List<String> allowedTopics = properties.getGroup().getAllowedTopics();
        if (allowedTopics == null || allowedTopics.isEmpty()) {
            return;
        }

        List<Map<String, String>> commands = buildCommandList();
        int registered = 0;
        for (String topic : allowedTopics) {
            try {
                long chatId = Long.parseLong(topic.trim());
                boolean ok = telegramClient.setMyCommandsForChat(chatId, commands);
                if (ok) {
                    registered++;
                    log.info("Registered {} commands for forum chat {}", commands.size(), chatId);
                } else {
                    log.warn("Failed to register commands for forum chat {}", chatId);
                }
            } catch (NumberFormatException e) {
                log.warn("Skipping forum command registration: '{}' is not a valid chat_id", topic);
            }
        }
        if (registered > 0) {
            log.info("Registered commands for {} forum chat(s)", registered);
        }
    }

    private List<Map<String, String>> buildCommandList() {
        return List.of(
            command("new", "Start new session (clear context)"),
            command("reset", "Full reset: new session, new history"),
            command("status", "Show current status"),
            command("stop", "Stop current turn"),
            command("help", "Show available commands"),
            command("model", "Switch model"),
            command("memory", "Manage memory: list, pending, approve, reject, approval, add, remove"),
            command("skills", "List loaded skills"),
            command("context", "Show session context"),
            command("usage", "Show token usage"),
            command("title", "Set session title"),
            command("sessions", "List saved sessions"),
            command("yolo", "Toggle approval gate"),
            command("verbose", "Toggle verbose mode"),
            command("fast", "Toggle fast mode"),
            command("reasoning", "Set reasoning level"),
            command("footer", "Toggle runtime footer"),
            command("resume", "Resume a previous session"),
            command("version", "Show agent version"),
            command("whoami", "Show your user info"),
            command("commands", "List all commands"),
            command("compress", "Compress session context"),
            command("undo", "Undo last N turns"),
            command("retry", "Retry last message"),
            command("approve", "Approve pending command"),
            command("deny", "Deny pending command"),
            command("agents", "List active agents"),
            command("insights", "Show usage insights"),
            // Phase 2: 10 new commands (A2.1-A2.10)
            command("profile", "Show active profile and home directory"),
            command("platform", "List connected platforms"),
            command("restart", "Restart the agent"),
            command("reload_mcp", "Reload MCP servers"),
            command("reload_skills", "Reload skills"),
            command("bundles", "List installed skill bundles"),
            command("branch", "Fork current session"),
            command("background", "Run prompt in background"),
            command("topic", "Manage DM topic sessions"),
            command("set_home", "Set current chat as home channel"),
            // Phase 3: 10 new commands (A3.1-A3.10)
            command("voice", "Voice mode (not supported)"),
            command("rollback", "Filesystem rollback (not available)"),
            command("credits", "Show usage balance"),
            command("update", "Show update instructions"),
            command("debug", "Show debug information"),
            command("codex_runtime", "Show or switch active model runtime"),
            command("personality", "Set or show agent personality"),
            command("kanban", "Show active agents and tasks"),
            command("goal", "Set or manage a standing goal"),
            command("subgoal", "Add criteria to the active goal"),
            command("steer", "Inject a mid-run note to the agent"),
            command("curator", "Skill maintenance: status, run, reload"),
            command("suggestions", "Review suggested automations")
        );
    }

    private void setupWebhook() {
        String url = properties.getWebhook().getUrl();
        String secret = properties.getWebhook().getSecret();

        if (url == null || url.isBlank()) {
            log.warn("Webhook mode selected but bot.webhook.url is empty");
            return;
        }
        if (secret == null || secret.isBlank()) {
            log.error("Webhook secret not configured, refusing to set webhook (fail-closed)");
            return;
        }

        // Delete stale webhook first
        telegramClient.deleteWebhook();

        boolean ok = telegramClient.setWebhook(url, secret);
        if (ok) {
            log.info("Webhook registered: {}", url);
        } else {
            log.error("Failed to register webhook");
        }
    }

    private Map<String, String> command(String name, String description) {
        Map<String, String> cmd = new LinkedHashMap<>();
        cmd.put("command", name);
        cmd.put("description", description);
        return cmd;
    }
}