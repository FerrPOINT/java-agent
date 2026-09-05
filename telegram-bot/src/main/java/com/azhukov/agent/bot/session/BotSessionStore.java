package com.azhukov.agent.bot.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service layer for managing bot sessions.
 * Wraps {@link BotSessionRepository} with higher-level operations used by commands and the message pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotSessionStore {

    private final BotSessionRepository repository;

    /**
     * Locks per userId to prevent race conditions on concurrent resolveOrCreate calls.
     * Without this, two concurrent calls for the same user could both find no existing
     * session and both create new ones, resulting in duplicate active sessions.
     */
    private final ConcurrentHashMap<String, Object> createLocks = new ConcurrentHashMap<>();

    /**
     * Find the active session for the given user, or create a new one.
     *
     * @param userId   Telegram user ID
     * @param chatId   Telegram chat ID
     * @param username Telegram username (may be null)
     * @return the active session entity
     */
    @Transactional
    public BotSessionEntity resolveOrCreate(String userId, String chatId, String username) {
        Optional<BotSessionEntity> existing = repository.findByUserIdAndActiveTrue(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Synchronize per userId to prevent duplicate session creation on concurrent calls.
        // The database unique constraint is the ultimate safety net, but this lock avoids
        // unnecessary constraint violation exceptions and duplicate write attempts.
        Object lock = createLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            // Double-check after acquiring the lock — another thread may have created the session
            existing = repository.findByUserIdAndActiveTrue(userId);
            if (existing.isPresent()) {
                return existing.get();
            }

            BotSessionEntity session = new BotSessionEntity();
            session.setUserId(userId);
            session.setChatId(chatId);
            session.setUsername(username);
            session.setActive(true);
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            return repository.save(session);
        }
    }

    /**
     * Create a brand-new ACTIVE session for the user (deactivating the current
     * one). Used by /topic create so each topic owns a distinct conversation.
     */
    @Transactional
    public BotSessionEntity createFreshSession(String userId, String chatId, String username) {
        repository.deactivateAllForUser(userId);
        BotSessionEntity session = new BotSessionEntity();
        session.setUserId(userId);
        session.setChatId(chatId);
        session.setUsername(username);
        session.setActive(true);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        return repository.save(session);
    }

    /**
     * Route the user's ACTIVE session to the given stored topic session row:
     * deactivate the current active row and re-activate the topic's row.
     */
    @Transactional
    public boolean activateSessionById(String userId, UUID sessionId) {
        Optional<BotSessionEntity> target = repository.findById(sessionId);
        if (target.isEmpty() || !target.get().getUserId().equals(userId)) {
            return false;
        }
        repository.deactivateAllForUser(userId);
        BotSessionEntity entity = target.get();
        entity.setActive(true);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        return true;
    }

    @Transactional
    public void updateTitle(UUID id, String title) {
        repository.findById(id).ifPresent(session -> {
            session.setTitle(title);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    @Transactional
    public boolean toggleYolo(UUID id) {
        BotSessionEntity session = repository.findById(id).orElse(null);
        if (session == null) return false;
        session.setYoloMode(!session.isYoloMode());
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session.isYoloMode();
    }

    @Transactional
    public boolean toggleVerbose(UUID id) {
        BotSessionEntity session = repository.findById(id).orElse(null);
        if (session == null) return false;
        session.setVerboseMode(!session.isVerboseMode());
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session.isVerboseMode();
    }

    @Transactional
    public boolean toggleFast(UUID id) {
        BotSessionEntity session = repository.findById(id).orElse(null);
        if (session == null) return false;
        session.setFastMode(!session.isFastMode());
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session.isFastMode();
    }

    @Transactional
    public boolean toggleFooter(UUID id) {
        BotSessionEntity session = repository.findById(id).orElse(null);
        if (session == null) return false;
        session.setFooterEnabled(!session.isFooterEnabled());
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session.isFooterEnabled();
    }

    @Transactional
    public BotSessionEntity resumeSession(UUID sessionId, String userId) {
        // Deactivate current active session
        BotSessionEntity current = repository.findByUserIdAndActiveTrue(userId).orElse(null);
        if (current != null) {
            current.setActive(false);
            current.setUpdatedAt(Instant.now());
            repository.save(current);
        }
        // Activate requested session
        BotSessionEntity target = repository.findById(sessionId).orElse(null);
        if (target == null) return null;
        target.setActive(true);
        target.setUpdatedAt(Instant.now());
        return repository.save(target);
    }

    @Transactional
    public boolean toggleVoiceMode(UUID id) {
        BotSessionEntity session = repository.findById(id).orElse(null);
        if (session == null) return false;
        session.setVoiceMode(!session.isVoiceMode());
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return session.isVoiceMode();
    }

    @Transactional
    public void setVoiceMode(UUID id, boolean enabled) {
        repository.findById(id).ifPresent(session -> {
            session.setVoiceMode(enabled);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    @Transactional
    public void setReasoningLevel(UUID id, String level) {
        repository.findById(id).ifPresent(session -> {
            session.setReasoningLevel(level);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    /**
     * Persist a metadata mutation made on a detached command-level entity.
     * Bot commands receive the session outside this store's transaction and
     * mutate {@code setMetadata(...)} in memory; without this call the change
     * is silently lost (the /goal false-success defect).
     */
    @Transactional
    public void persistMetadata(UUID id, String key, String value) {
        repository.findById(id).ifPresent(session -> {
            session.setMetadata(key, value);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    @Transactional
    public void setModelOverride(UUID id, String model) {
        repository.findById(id).ifPresent(session -> {
            session.setModelOverride(model);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    @Transactional(readOnly = true)
    public List<BotSessionEntity> listByUserId(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional
    public int deactivateAll(String userId) {
        return repository.deactivateAllForUser(userId);
    }

    // ─── P0: Session Reset Policy ──────────────────────────────────

    /**
     * List all active sessions (for the expiry watcher).
     *
     * @return list of active sessions
     */
    @Transactional(readOnly = true)
    public java.util.List<BotSessionEntity> listActiveSessions() {
        return repository.findByActiveTrue();
    }

    /**
     * Finalize an expired session — deactivate it and update timestamp.
     *
     * @param sessionId the session UUID
     */
    @Transactional
    public void finalizeSession(UUID sessionId) {
        repository.findById(sessionId).ifPresent(session -> {
            session.setActive(false);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }

    /**
     * Suspend a session — marks it for auto-reset on next access.
     * Used by /stop to break stuck-resume loops.
     *
     * @param sessionId the session UUID
     * @return true if the session was found and suspended
     */
    @Transactional
    public boolean suspendSession(UUID sessionId) {
        BotSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null) return false;
        session.setSuspended(true);
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return true;
    }

    /**
     * Mark a session as resume-pending — preserves the session_id
     * so the user auto-continues from where they left off after a restart.
     *
     * @param sessionId the session UUID
     * @return true if the session was found and marked
     */
    @Transactional
    public boolean markResumePending(UUID sessionId) {
        BotSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null) return false;
        if (session.isSuspended()) return false; // Never override explicit suspend
        session.setResumePending(true);
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return true;
    }

    /**
     * Clear the resume-pending flag after a successful resumed turn.
     *
     * @param sessionId the session UUID
     * @return true if the flag was cleared
     */
    @Transactional
    public boolean clearResumePending(UUID sessionId) {
        BotSessionEntity session = repository.findById(sessionId).orElse(null);
        if (session == null || !session.isResumePending()) return false;
        session.setResumePending(false);
        session.setUpdatedAt(Instant.now());
        repository.save(session);
        return true;
    }

    /**
     * Update the backend session ID for a bot session. Called after the first
     * backend interaction to persist the UUID that the backend assigned, so
     * subsequent requests can use it for conversation history continuity.
     *
     * @param botSessionId    the bot session's own UUID
     * @param backendSessionId the backend-assigned session UUID
     */
    @Transactional
    public void updateBackendSessionId(UUID botSessionId, UUID backendSessionId) {
        repository.findById(botSessionId).ifPresent(session -> {
            session.setBackendSessionId(backendSessionId);
            session.setUpdatedAt(Instant.now());
            repository.save(session);
        });
    }
}