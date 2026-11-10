package com.azhukov.agent.api;

import com.azhukov.agent.service.ProfileService;

import java.io.IOException;

final class EventSubscription {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private EventSubscription() {}

    static String resolveProfileScope(ProfileService profileService, String pathProfile, String queryProfile) throws IOException {
        if (hasText(pathProfile)) {
            return requireKnownProfile(profileService, pathProfile);
        }
        if (!hasText(queryProfile) || "all".equalsIgnoreCase(queryProfile.trim())) {
            return null;
        }
        return requireKnownProfile(profileService, queryProfile);
    }

    private static String requireKnownProfile(ProfileService profileService, String rawProfile) throws IOException {
        String profile = profileService.normalizeProfileName(rawProfile);
        profileService.validateProfileName(profile);
        profileService.requireKnownProfile(profile);
        return profile;
    }

    static long parseCursor(String raw) {
        if (!hasText(raw)) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0L) {
                throw new IllegalArgumentException("cursor must be greater than or equal to 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor must be an integer");
        }
    }

    static int parseLimit(String raw) {
        if (!hasText(raw)) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1 || parsed > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
