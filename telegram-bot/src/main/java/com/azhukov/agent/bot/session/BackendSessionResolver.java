package com.azhukov.agent.bot.session;

import java.util.UUID;

/**
 * Resolves the backend session identifier that bot commands must send to the
 * backend API. The bot's local {@code bot_sessions.id} is NOT the backend
 * conversation id: the backend assigns its own session UUID on the first chat
 * turn ({@code BotMessageProcessor} persists it via
 * {@link BotSessionStore#updateBackendSessionId}). Commands that operated on
 * the local id silently targeted nonexistent backend sessions.
 *
 * <p>Resolution rule (mirrors {@code HeartbeatCommand}/{@code LoopCommand}):
 * use the stored backend session id when present; before the first turn there
 * is no backend conversation yet, so callers receive {@code null} and must
 * handle it (typically "no conversation yet").
 */
public final class BackendSessionResolver {

    private BackendSessionResolver() {
    }

    /**
     * @return the backend conversation id, or {@code null} when this bot
     *         session has not yet had its first turn (no backend session
     *         exists yet).
     */
    public static UUID resolve(BotSessionEntity session) {
        if (session == null) {
            return null;
        }
        UUID backend = session.getBackendSessionId();
        return backend != null ? backend : null;
    }

    /**
     * @return the backend conversation id as a String, or {@code null}.
     */
    public static String resolveString(BotSessionEntity session) {
        UUID backend = resolve(session);
        return backend != null ? backend.toString() : null;
    }
}
