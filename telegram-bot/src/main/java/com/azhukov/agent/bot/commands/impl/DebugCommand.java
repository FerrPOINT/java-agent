package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A3.5: /debug — Gather config summary and recent info, return as text.
 * Uses BotProperties to show non-secret configuration.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DebugCommand implements CommandHandler {

    private final BotProperties properties;

    

    @Override
    public String name() {
        return "debug";
    }

    @Override
    public String description() {
        return "Show debug information";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        StringBuilder sb = new StringBuilder("=== Debug Info ===\n");
        sb.append("Timestamp: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");

        // Bot configuration (non-secret)
        sb.append("== Configuration ==\n");
        sb.append("  mode: ").append(properties.getMode()).append("\n");
        sb.append("  agent-name: ").append(properties.getAgentName()).append("\n");
        sb.append("  backend-url: ").append(properties.getBackendUrl()).append("\n");
        sb.append("  max-message-length: ").append(properties.getMaxMessageLength()).append("\n");
        sb.append("  busy-mode: ").append(properties.getBusyMode()).append("\n");
        sb.append("  parse-mode: ").append(properties.getParseMode()).append("\n");
        sb.append("  register-commands: ").append(properties.isRegisterCommands()).append("\n");
        sb.append("  rate-limit-per-second: ").append(properties.getRateLimitPerSecond()).append("\n");
        sb.append("  default-model: ").append(properties.getDefaultModel()).append("\n");
        sb.append("  reply-to-mode: ").append(properties.getReplyToMode()).append("\n");
        sb.append("  link-preview: ").append(properties.isLinkPreview()).append("\n");
        sb.append("  home-chat-id: ").append(properties.getHomeChatId()).append("\n");

        // Group config
        sb.append("\n== Group ==\n");
        sb.append("  require-mention: ").append(properties.getGroup().isRequireMention()).append("\n");
        sb.append("  guest-mode: ").append(properties.getGroup().isGuestMode()).append("\n");
        sb.append("  observe-unmentioned: ").append(properties.getGroup().isObserveUnmentioned()).append("\n");
        sb.append("  exclusive-bot-mentions: ").append(properties.getGroup().isExclusiveBotMentions()).append("\n");
        sb.append("  free-response-chats: ").append(properties.getGroup().getFreeResponseChats()).append("\n");
        sb.append("  allowed-topics: ").append(properties.getGroup().getAllowedTopics()).append("\n");
        sb.append("  ignored-threads: ").append(properties.getGroup().getIgnoredThreads()).append("\n");
        sb.append("  dm-topics: ").append(properties.getGroup().getDmTopics().size()).append(" configured\n");

        // Polling config
        sb.append("\n== Polling ==\n");
        sb.append("  timeout-seconds: ").append(properties.getPolling().getTimeoutSeconds()).append("\n");
        sb.append("  limit: ").append(properties.getPolling().getLimit()).append("\n");
        sb.append("  reconnect-delay-ms: ").append(properties.getPolling().getReconnectDelayMs()).append("\n");

        // Display config
        sb.append("\n== Display ==\n");
        sb.append("  tool-progress: ").append(properties.getDisplay().getToolProgress()).append("\n");

        // Footer config
        sb.append("\n== Footer ==\n");
        sb.append("  enabled: ").append(properties.getFooter().isEnabled()).append("\n");
        sb.append("  fields: ").append(properties.getFooter().getFields()).append("\n");

        // Token is intentionally omitted for security
        sb.append("\n== Security ==\n");
        sb.append("  token: [REDACTED]\n");

        return sb.toString();
    }
}