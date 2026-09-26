package com.azhukov.agent.bot.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Active-session recovery (docs/34 gap 9 remainder, Hermes parity).
 *
 * <p>Hermes survives gateway restarts by reconnecting the user's active
 * conversation. The Java bot keeps sessions in {@code bot_sessions}; what was
 * missing is the restart handshake:
 *
 * <ol>
 *   <li><b>Graceful shutdown</b> — every active, non-suspended session is
 *       marked {@code resume_pending} so the next boot knows a turn may have
 *       been interrupted mid-stream. Already-pending and suspended sessions
 *       are never touched (explicit user intent wins over recovery).</li>
 *   <li><b>Startup</b> — pending sessions are recovered once: the bot gets
 *       the chat ids to notify, the flag is cleared immediately so the notice
 *       can never repeat on subsequent boots, and the user simply continues
 *       in the same session ({@code resolveOrCreate} returns it as the active
 *       session, preserving history).</li>
 * </ol>
 *
 * <p>The service is deliberately storage-only; sending the notice is the
 * caller's job (keeps this unit-testable and transport-agnostic).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionRecoveryService {

    private final BotSessionRepository repository;
    private final BotSessionStore store;

    /**
     * Recover resume-pending sessions at startup: collect chat ids for the
     * one-time notice and clear the flag transactionally.
     *
     * @return recovered (chatId, backendSessionId) pairs the caller should notify
     */
    @Transactional
    public List<RecoveredSession> recoverPendingSessions() {
        List<BotSessionEntity> pending = repository.findByResumePendingTrueAndActiveTrue();
        if (pending.isEmpty()) {
            return List.of();
        }
        List<RecoveredSession> recovered = pending.stream()
                .map(session -> new RecoveredSession(
                        session.getChatId(),
                        session.getLastMessageThreadId(),
                        session.getBackendSessionId(),
                        session.getId()))
                .toList();
        log.info("startup: found {} resume-pending session(s)", recovered.size());
        return recovered;
    }

    /**
     * Clear resume-pending only after the recovery notice has reached Telegram.
     * A failed delivery remains pending for the next restart instead of silently
     * discarding the interrupted-turn signal.
     */
    @Transactional
    public boolean acknowledgeRecoveredSession(java.util.UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        return store.clearResumePending(sessionId);
    }

    /** A session recovered at startup, ready for a one-time continuation notice. */
    public record RecoveredSession(String chatId, long messageThreadId,
                                   java.util.UUID backendSessionId, java.util.UUID botSessionId) {
    }

    /** The one-time notice text sent to recovered chats after a restart. */
    public static String recoveryNotice() {
        return "\u26A0\uFE0F The bot was restarted while your session was active. "
                + "Your conversation history is preserved \u2014 just send a message to continue.";
    }
}
