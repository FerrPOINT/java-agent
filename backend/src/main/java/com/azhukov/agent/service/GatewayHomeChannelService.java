package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.GatewayHomeChannelEntity;
import com.azhukov.agent.persistence.repository.GatewayHomeChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persisted home-channel directory (ADR-012, Hermes {@code HomeChannel} /
 * {@code persist_home_channel} parity).
 *
 * <p>Single authority for bare-platform target resolution: {@code deliver=
 * "telegram"} and {@code send_message target="telegram"} resolve through the
 * persisted row. While the table is empty the legacy first-allowed-user-id
 * heuristic still answers (single-profile dev deployments keep working);
 * once a home is persisted it wins.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GatewayHomeChannelService {

    public static final String DEFAULT_PROFILE = "default";

    private final GatewayHomeChannelRepository repository;
    private final AgentProperties properties;

    /** Resolved home target: chat id plus optional topic thread id. */
    public record HomeTarget(String chatId, String threadId, String name, String userId, boolean persisted) {}

    @Transactional(readOnly = true)
    public Optional<HomeTarget> resolve(String platform, String profile) {
        String normalizedPlatform = normalizePlatform(platform);
        String normalizedProfile = normalizeProfile(profile);
        if (repository != null) {
            Optional<GatewayHomeChannelEntity> row =
                repository.findByPlatformAndProfile(normalizedPlatform, normalizedProfile);
            if (row.isPresent()) {
                GatewayHomeChannelEntity entity = row.get();
                return Optional.of(new HomeTarget(
                    entity.getChatId(),
                    entity.getThreadId(),
                    entity.getName(),
                    entity.getUserId(),
                    true));
            }
        }
        // Legacy fallback (pre-WP-2 single-profile deployments): first numeric
        // allowed user id. Logged so operators can see it is time to /set_home.
        if (DEFAULT_PROFILE.equals(normalizedProfile) && "telegram".equals(normalizedPlatform)) {
            String ownerChat = legacyOwnerChat();
            if (ownerChat != null) {
                log.debug("Home channel for telegram/default not persisted; using legacy first-allowed-user-id {}", ownerChat);
                return Optional.of(new HomeTarget(ownerChat, null, "legacy-allowed-user", null, false));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public HomeTarget setHome(String platform, String profile, String chatId, String threadId,
                              String name, String userId, String updatedBy) {
        String normalizedPlatform = normalizePlatform(platform);
        String normalizedProfile = normalizeProfile(profile);
        if (isBlank(chatId)) {
            throw new IllegalArgumentException("chat_id is required");
        }
        GatewayHomeChannelEntity entity = repository.findByPlatformAndProfile(normalizedPlatform, normalizedProfile)
            .orElseGet(() -> {
                GatewayHomeChannelEntity created = new GatewayHomeChannelEntity();
                created.setPlatform(normalizedPlatform);
                created.setProfile(normalizedProfile);
                return created;
            });
        entity.setChatId(chatId.trim());
        entity.setThreadId(isBlank(threadId) ? null : threadId.trim());
        entity.setName(isBlank(name) ? null : name.trim());
        entity.setUserId(isBlank(userId) ? null : userId.trim());
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(isBlank(updatedBy) ? null : updatedBy.trim());
        repository.save(entity);
        log.info("Home channel persisted: {}/{} -> chat {} thread {} (by {})",
            normalizedPlatform, normalizedProfile, entity.getChatId(), entity.getThreadId(), entity.getUpdatedBy());
        return new HomeTarget(entity.getChatId(), entity.getThreadId(), entity.getName(), entity.getUserId(), true);
    }

    @Transactional
    public boolean clearHome(String platform, String profile) {
        return repository.deleteByPlatformAndProfile(normalizePlatform(platform), normalizeProfile(profile)) > 0;
    }

    @Transactional(readOnly = true)
    public List<GatewayHomeChannelEntity> all() {
        return repository.findAllBy();
    }

    private String legacyOwnerChat() {
        var allowed = properties.getGateway().getTelegram().getAllowedUserIds();
        if (allowed == null) {
            return null;
        }
        for (String candidate : allowed) {
            String trimmed = candidate == null ? "" : candidate.trim();
            if (trimmed.matches("\\d+")) {
                return trimmed;
            }
        }
        return null;
    }

    private static String normalizePlatform(String platform) {
        if (isBlank(platform)) {
            return "telegram";
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeProfile(String profile) {
        return isBlank(profile) ? DEFAULT_PROFILE : profile.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
