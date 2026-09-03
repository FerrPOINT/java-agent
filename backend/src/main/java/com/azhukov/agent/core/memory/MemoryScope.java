package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Resolves the storage key used by built-in memory for a runtime session.
 */
public final class MemoryScope {

    private static final String PROFILE_SEPARATOR = "::profile::";
    private static final String INVALID_PROFILE_SCOPE = "__invalid_profile__";
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private MemoryScope() {
    }

    public static String userId(Session session) {
        return userId(session, null);
    }

    public static String userId(Session session, AgentProperties properties) {
        String profile = session != null ? session.getMetadata("profile") : null;
        if ((profile == null || profile.isBlank()) && properties != null && properties.getProfile() != null) {
            profile = properties.getProfile().getName();
        }
        String baseUserId = session == null || session.userId() == null || session.userId().isBlank()
            ? AgentProperties.DEFAULT_USER_ID
            : session.userId();
        return userId(baseUserId, profile);
    }

    public static String userId(String userId, String profile) {
        String baseUserId = userId == null || userId.isBlank()
            ? AgentProperties.DEFAULT_USER_ID
            : userId;
        String normalizedProfile = normalizeProfile(profile);
        if ("default".equals(normalizedProfile)) {
            return baseUserId;
        }
        return baseUserId + PROFILE_SEPARATOR + normalizedProfile;
    }

    public static String normalizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "default";
        }
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "default";
        }
        if ("default".equals(normalized) || PROFILE_ID.matcher(normalized).matches()) {
            return normalized;
        }
        return INVALID_PROFILE_SCOPE;
    }
}
