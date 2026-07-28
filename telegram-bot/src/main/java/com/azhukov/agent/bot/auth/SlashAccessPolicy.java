package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SlashAccessPolicy {

    private static final Set<String> ALWAYS_ALLOWED = Set.of("help", "whoami");

    private final BotProperties properties;

    public SlashAccessPolicy(BotProperties properties) {
        this.properties = properties;
    }

    public boolean canRun(long userId, String commandName) {
        if (commandName == null) return false;
        // Always-allowed commands
        if (ALWAYS_ALLOWED.contains(commandName)) return true;

        Set<String> adminIds = properties.getAuth().getAdminUserIds().stream()
            .collect(Collectors.toSet());
        // If no admin IDs configured → gating disabled (backward compat)
        if (adminIds.isEmpty()) return true;

        String userIdStr = String.valueOf(userId);
        // Admins can run everything
        if (adminIds.contains(userIdStr)) return true;

        // Non-admins: check user-allowed-commands list
        Set<String> allowed = properties.getAuth().getUserAllowedCommands().stream()
            .collect(Collectors.toSet());
        return allowed.contains(commandName);
    }

    public String accessLevel(long userId) {
        Set<String> adminIds = properties.getAuth().getAdminUserIds().stream()
            .collect(Collectors.toSet());
        if (adminIds.isEmpty()) return "user";
        String userIdStr = String.valueOf(userId);
        if (adminIds.contains(userIdStr)) return "admin";
        Set<String> allowed = properties.getAuth().getUserAllowedCommands().stream()
            .collect(Collectors.toSet());
        if (!allowed.isEmpty()) return "user";
        return "none";
    }
}