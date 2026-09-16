package com.azhukov.agent.gateway;

import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.service.GatewayHomeChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Gateway target resolution (WP-2, Hermes {@code DeliveryTarget.parse} parity).
 *
 * <p>Accepted shapes:
 * <ul>
 *   <li>{@code platform:chat_id} — explicit chat;</li>
 *   <li>{@code platform:chat_id:thread_id} — explicit chat + forum topic/thread;</li>
 *   <li>{@code platform} — bare platform: resolves to the persisted home channel
 *       ({@link GatewayHomeChannelService}), legacy allowed-user fallback when
 *       nothing is persisted yet;</li>
 *   <li>{@code origin} — resolved by the caller from the session origin before
 *       this point (see {@code CronJobService}, {@code DeliveryWorkItemService}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayTargetResolver {

    private final ObjectProvider<GatewayHomeChannelService> homeChannelProvider;

    /** Fully resolved outbound target. */
    public record ResolvedTarget(Platform platform, String chatId, String threadId, boolean homeResolved) {

        public SessionSource toSessionSource() {
            return new SessionSource(platform, chatId, null, null, null, threadId);
        }
    }

    /**
     * Parse and resolve a send_message/deliver target string. Returns empty
     * (with the reason logged) when the shape is unknown or a bare platform
     * has no resolvable home.
     */
    public Optional<ResolvedTarget> resolve(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        String trimmed = target.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("origin".equals(lower) || "local".equals(lower)) {
            // origin must be resolved from session context before this call;
            // local is not a platform target.
            return Optional.empty();
        }
        String[] parts = trimmed.split(":", 3);
        Platform platform = parsePlatform(parts[0]);
        if (platform == null) {
            return Optional.empty();
        }
        if (parts.length == 1) {
            return resolveHome(platform);
        }
        String chatId = parts[1].trim();
        if (chatId.isEmpty()) {
            return resolveHome(platform);
        }
        String threadId = parts.length == 3 && !parts[2].isBlank() ? parts[2].trim() : null;
        return Optional.of(new ResolvedTarget(platform, chatId, threadId, false));
    }

    /** Bare platform: persisted home channel, else legacy fallback. */
    public Optional<ResolvedTarget> resolveHome(Platform platform) {
        GatewayHomeChannelService homeChannels = homeChannelProvider.getIfAvailable();
        if (homeChannels == null) {
            return Optional.empty();
        }
        return homeChannels.resolve(platform.name().toLowerCase(Locale.ROOT), GatewayHomeChannelService.DEFAULT_PROFILE)
            .map(home -> new ResolvedTarget(platform, home.chatId(), home.threadId(), true));
    }

    public static Platform parsePlatform(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Platform.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
