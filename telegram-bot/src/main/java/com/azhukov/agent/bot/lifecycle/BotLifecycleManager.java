package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BotLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(BotLifecycleManager.class);

    private final TelegramClient telegramClient;
    private final BotProperties properties;

    public BotLifecycleManager(TelegramClient telegramClient, BotProperties properties) {
        this.telegramClient = telegramClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.warn("Bot token is empty, skipping lifecycle setup");
            return;
        }

        if (properties.isRegisterCommands()) {
            registerCommands();
        }

        if ("webhook".equals(properties.getMode())) {
            setupWebhook();
        }
        // For polling mode, LongPollingService handles its own startup
    }

    private void registerCommands() {
        List<Map<String, String>> commands = List.of(
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
            command("credits", "Credit balance (not available)"),
            command("update", "Show update instructions"),
            command("debug", "Show debug information"),
            command("codex_runtime", "Codex runtime (not supported)"),
            command("personality", "Personality system (not available)"),
            command("kanban", "Kanban integration (not available)"),
            command("goal", "Goal management (not available)"),
            command("subgoal", "Subgoal management (not available)")
        );

        boolean ok = telegramClient.setMyCommands(commands);
        if (ok) {
            log.info("Registered {} bot commands", commands.size());
        } else {
            log.warn("Failed to register bot commands");
        }
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