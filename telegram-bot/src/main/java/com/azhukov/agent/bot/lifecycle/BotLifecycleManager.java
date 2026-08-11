package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    private final CommandRegistry commandRegistry;

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

    /**
     * Dynamically build the command list from {@link CommandRegistry#all()}.
     * Each command entry contains the command name and description.
     */
    private List<Map<String, String>> buildCommandList() {
        return commandRegistry.all().stream()
            .map(handler -> command(handler.name(), handler.description()))
            .toList();
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