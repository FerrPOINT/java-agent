package com.azhukov.agent.bot.session;

/**
 * Session lifecycle states.
 *
 * <p>Tracks the current state of a session in the BotSessionEntity:
 * <ul>
 *   <li>{@code ACTIVE}: Normal operation — session is live and accepting messages</li>
 *   <li>{@code SUSPENDED}: Session was explicitly suspended (e.g. by /stop).
 *       The next message auto-resets the session.</li>
 *   <li>{@code RESUME_PENDING}: Session was interrupted by a restart/shutdown
 *       but recovery is still expected. Preserves the existing session_id
 *       so the user auto-continues from where they left off.</li>
 * </ul>
 *
 * <p>Mirrors the Python {@code suspended} / {@code resume_pending} flags in
 * {@code gateway/session.py}.
 */
public enum SessionState {
    ACTIVE,
    SUSPENDED,
    RESUME_PENDING
}