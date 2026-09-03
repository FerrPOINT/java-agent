package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;

import java.util.UUID;

/**
 * Run-scoped control identity for API runs that share a persisted session.
 */
public final class RunControlScope {

    public static final String METADATA_KEY = "api_run_control_id";

    private RunControlScope() {
    }

    public static Session withControlSessionId(Session session, UUID controlSessionId) {
        if (session == null || controlSessionId == null) {
            return session;
        }
        return session.withMetadata(METADATA_KEY, controlSessionId.toString());
    }

    public static boolean hasControlSessionId(Session session) {
        return session != null
            && session.getMetadata(METADATA_KEY) != null
            && !session.getMetadata(METADATA_KEY).isBlank();
    }

    public static UUID controlSessionId(Session session) {
        if (session == null) {
            return null;
        }
        String raw = session.getMetadata(METADATA_KEY);
        if (raw == null || raw.isBlank()) {
            return session.id();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return session.id();
        }
    }
}
