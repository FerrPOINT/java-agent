package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Narrow persisted config-write boundary for gateway settings (WP-2, ADR-012).
 *
 * <p>Until WP-4 delivers the full profile config runtime, Telegram pairing
 * (allowlist) and messaging config writes go through this writer: mutate the
 * runtime {@code AgentProperties} AND persist the same values into
 * {@code profiles/<name>/config.yaml} under {@code gateway.telegram} via the
 * existing atomic {@link ProfileService#writeConfig} writer. Reads stay on
 * {@code AgentProperties} (env/yml precedence is unchanged); the persisted
 * section is the durable record for future profile-scoped runtimes.
 *
 * <p>Secrets (bot token) are persisted to the profile config only when
 * explicitly written through this boundary; they are never returned in
 * dashboard read payloads (masked upstream in the controller).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayConfigWriter {

    private final AgentProperties agentProperties;
    private final ObjectProvider<ProfileService> profileServiceProvider;

    /** Approve = add user id (or @username) to the Telegram allowlist. */
    public ApproveResult approveTelegramUser(String profile, String userValue) throws IOException {
        String clean = requireUserValue(userValue);
        var telegram = agentProperties.getGateway().getTelegram();
        boolean added = false;
        if (clean.startsWith("@")) {
            String normalized = clean.substring(1);
            if (telegram.getAllowedUsernames().stream().noneMatch(normalized::equals)) {
                telegram.getAllowedUsernames().add(normalized);
                added = true;
            }
        } else {
            if (telegram.getAllowedUserIds().stream().noneMatch(clean::equals)) {
                telegram.getAllowedUserIds().add(clean);
                added = true;
            }
        }
        persistTelegramAllowlist(profile);
        log.info("Telegram pairing approved for {} (added={})", clean, added);
        return new ApproveResult(clean, added);
    }

    /** Revoke = remove user id (or @username) from the Telegram allowlist. */
    public boolean revokeTelegramUser(String profile, String userValue) throws IOException {
        String clean = requireUserValue(userValue);
        var telegram = agentProperties.getGateway().getTelegram();
        boolean removed;
        if (clean.startsWith("@")) {
            String normalized = clean.substring(1);
            removed = telegram.getAllowedUsernames().removeIf(normalized::equals);
        } else {
            removed = telegram.getAllowedUserIds().removeIf(clean::equals);
        }
        if (removed) {
            persistTelegramAllowlist(profile);
            log.info("Telegram pairing revoked for {}", clean);
        }
        return removed;
    }

    /** Write Telegram runtime settings (token/allowlist/webhook secrets stay masked in reads). */
    public Map<String, Object> updateTelegramConfig(String profile, String botToken,
                                                    List<String> allowedUserIds,
                                                    List<String> allowedUsernames,
                                                    Boolean allowByDefault) throws IOException {
        var telegram = agentProperties.getGateway().getTelegram();
        if (botToken != null && !botToken.isBlank()) {
            telegram.setBotToken(botToken.trim());
        }
        if (allowedUserIds != null) {
            telegram.getAllowedUserIds().clear();
            allowedUserIds.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .forEach(telegram.getAllowedUserIds()::add);
        }
        if (allowedUsernames != null) {
            telegram.getAllowedUsernames().clear();
            allowedUsernames.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                .forEach(telegram.getAllowedUsernames()::add);
        }
        if (allowByDefault != null) {
            telegram.setAllowByDefault(allowByDefault);
        }
        persistTelegramSection(profile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("platform", "telegram");
        result.put("bot_token_set", hasText(telegram.getBotToken()));
        result.put("allowed_user_ids", List.copyOf(telegram.getAllowedUserIds()));
        result.put("allowed_usernames", List.copyOf(telegram.getAllowedUsernames()));
        result.put("allow_by_default", telegram.isAllowByDefault());
        return result;
    }

    public record ApproveResult(String user, boolean added) {}

    // ---- persistence ----

    private void persistTelegramAllowlist(String profile) throws IOException {
        persistTelegramSection(profile);
    }

    @SuppressWarnings("unchecked")
    private void persistTelegramSection(String profile) throws IOException {
        if (profileServiceProvider == null) {
            log.debug("ProfileService provider unavailable; gateway config change stays runtime-only");
            return;
        }
        ProfileService profiles = profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            log.debug("ProfileService unavailable; gateway config change stays runtime-only");
            return;
        }
        String canon = profiles.normalizeProfileName(profile == null || profile.isBlank() ? "default" : profile);
        Map<String, Object> config = profiles.readConfig(canon);
        Map<String, Object> gateway = asMutableMap(config.computeIfAbsent("gateway", k -> new LinkedHashMap<>()));
        var telegram = agentProperties.getGateway().getTelegram();
        Map<String, Object> telegramSection =
            asMutableMap(gateway.computeIfAbsent("telegram", k -> new LinkedHashMap<>()));
        telegramSection.put("allowed-user-ids", new ArrayList<>(telegram.getAllowedUserIds()));
        telegramSection.put("allowed-usernames", new ArrayList<>(telegram.getAllowedUsernames()));
        telegramSection.put("allow-by-default", telegram.isAllowByDefault());
        if (hasText(telegram.getBotToken())) {
            telegramSection.put("bot-token", telegram.getBotToken());
        }
        profiles.writeConfig(canon, config);
    }

    private static Map<String, Object> asMutableMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static String requireUserValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
