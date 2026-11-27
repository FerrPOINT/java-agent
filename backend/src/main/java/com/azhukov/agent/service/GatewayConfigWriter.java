package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.AgentProperties.TelegramProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class GatewayConfigWriter {

    static final String TOKEN = "TELEGRAM_BOT_TOKEN";
    static final String WEBHOOK_SECRET = "TELEGRAM_WEBHOOK_SECRET";
    static final String USER_IDS = "TELEGRAM_ALLOWED_USERS";
    static final String USERNAMES = "TELEGRAM_ALLOWED_USERNAMES";
    static final String ALLOW_BY_DEFAULT = "TELEGRAM_ALLOW_BY_DEFAULT";

    private final AgentProperties agentProperties;
    private final ProfileEnvStore env;
    private final ProfileRuntimeRegistry runtime;

    public TelegramAccess telegramAccess(String profile) throws IOException {
        if (isDefault(profile)) {
            synchronized (runtime.lockFor("default")) {
                return access(agentProperties.getGateway().getTelegram());
            }
        }
        return access(profileTelegram(profile));
    }

    public TelegramProperties profileTelegram(String profile) throws IOException {
        if (isDefault(profile)) {
            throw new IllegalArgumentException("A named Telegram profile is required");
        }
        return namedSettings(env.readAll(profile));
    }

    public ApproveResult approveTelegramUser(String profile, String userValue) throws IOException {
        String user = requireUserValue(userValue);
        return update(profile, telegram -> {
            var users = allowlist(telegram, user);
            String value = pairValue(user);
            boolean added = !users.contains(value) && users.add(value);
            return new ApproveResult(user, added, access(telegram));
        });
    }

    public ApproveResult revokeTelegramUser(String profile, String userValue) throws IOException {
        String user = requireUserValue(userValue);
        return update(profile, telegram -> {
            boolean removed = allowlist(telegram, user).remove(pairValue(user));
            return new ApproveResult(user, removed, access(telegram));
        });
    }

    public TelegramAccess updateTelegramConfig(String profile, String botToken, String webhookSecret,
                                               List<String> allowedUserIds, List<String> allowedUsernames,
                                               Boolean allowByDefault) throws IOException {
        if (hasText(webhookSecret)) {
            validateWebhookSecret(webhookSecret);
        }
        List<String> ids = allowedUserIds == null ? null : allowedUserIds.stream()
            .filter(GatewayConfigWriter::hasText).map(GatewayConfigWriter::requireUserValue).distinct().toList();
        List<String> usernames = allowedUsernames == null ? null : allowedUsernames.stream()
            .filter(GatewayConfigWriter::hasText).map(GatewayConfigWriter::requireUserValue)
            .map(GatewayConfigWriter::pairValue).distinct().toList();
        return update(profile, telegram -> {
            if (hasText(botToken)) {
                telegram.setBotToken(botToken.trim());
            }
            if (hasText(webhookSecret)) {
                telegram.setWebhookSecret(webhookSecret);
            }
            replaceAllowlist(telegram.getAllowedUserIds(), ids);
            replaceAllowlist(telegram.getAllowedUsernames(), usernames);
            if (allowByDefault != null) {
                telegram.setAllowByDefault(allowByDefault);
            }
            return access(telegram);
        });
    }

    public static boolean isDefault(String profile) {
        return profile == null || profile.isBlank() || "default".equalsIgnoreCase(profile.trim());
    }

    private <T> T update(String profile, Function<TelegramProperties, T> edit) throws IOException {
        if (isDefault(profile)) {
            synchronized (runtime.lockFor("default")) {
                return edit.apply(agentProperties.getGateway().getTelegram());
            }
        }
        return env.mutate(profile, values -> {
            var telegram = namedSettings(values);
            T result = edit.apply(telegram);
            if (hasText(telegram.getBotToken())) {
                values.put(TOKEN, telegram.getBotToken());
            }
            if (hasText(telegram.getWebhookSecret())) {
                values.put(WEBHOOK_SECRET, telegram.getWebhookSecret());
            }
            values.put(USER_IDS, String.join(",", telegram.getAllowedUserIds()));
            values.put(USERNAMES, String.join(",", telegram.getAllowedUsernames()));
            values.put(ALLOW_BY_DEFAULT, Boolean.toString(telegram.isAllowByDefault()));
            return result;
        });
    }

    private static TelegramProperties namedSettings(Map<String, String> values) {
        var config = new TelegramProperties();
        config.setBotToken(values.getOrDefault(TOKEN, ""));
        config.setWebhookSecret(values.getOrDefault(WEBHOOK_SECRET, ""));
        config.getAllowedUserIds().addAll(parseList(values.get(USER_IDS)));
        config.getAllowedUsernames().addAll(parseList(values.get(USERNAMES)));
        config.setAllowByDefault("true".equals(values.get(ALLOW_BY_DEFAULT)));
        return config;
    }

    private static List<String> parseList(String value) {
        return hasText(value)
            ? Arrays.stream(value.split(",")).map(String::trim).filter(GatewayConfigWriter::hasText).distinct().toList()
            : List.of();
    }

    private static TelegramAccess access(TelegramProperties telegram) {
        return new TelegramAccess(hasText(telegram.getBotToken()), hasText(telegram.getWebhookSecret()),
            List.copyOf(telegram.getAllowedUserIds()),
            List.copyOf(telegram.getAllowedUsernames()), telegram.isAllowByDefault());
    }

    public static boolean isAllowed(TelegramProperties settings, String userId, String username) {
        return settings.isAllowByDefault()
            || (userId != null && !userId.isBlank() && settings.getAllowedUserIds().contains(userId))
            || (username != null && !username.isBlank() && settings.getAllowedUsernames().contains(username));
    }

    private static List<String> allowlist(TelegramProperties telegram, String user) {
        return user.startsWith("@") ? telegram.getAllowedUsernames() : telegram.getAllowedUserIds();
    }

    private static void replaceAllowlist(List<String> target, List<String> value) {
        if (value != null) {
            target.clear();
            target.addAll(value);
        }
    }

    private static String pairValue(String user) {
        return user.startsWith("@") ? user.substring(1) : user;
    }

    private static String requireUserValue(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("user_id is required");
        }
        String user = value.trim();
        if (user.equals("@") || user.indexOf(',') >= 0 || user.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Telegram user or username");
        }
        return user;
    }

    static void validateWebhookSecret(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new IllegalArgumentException("Invalid Telegram webhook secret");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record TelegramAccess(boolean botTokenSet, boolean webhookSecretSet, List<String> userIds, List<String> usernames,
                                 boolean allowByDefault) {}

    public record ApproveResult(String user, boolean changed, TelegramAccess access) {}
}
