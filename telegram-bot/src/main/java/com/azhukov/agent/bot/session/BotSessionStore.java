package com.azhukov.agent.bot.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for managing bot sessions.
 * Wraps {@link BotSessionRepository} with higher-level operations used by commands and the message pipeline.
 */
@Service
public class BotSessionStore {

    private static final Logger log = LoggerFactory.getLogger(BotSessionStore.class);

    private final BotSessionRepository repository;

    public BotSessionStore(BotSessionRepository repository) {
        this.repository = repository;
    }

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

        BotSessionEntity session = new BotSessionEntity();
        session.setUserId(userId);
        session.setChatId(chatId);
        session.setUsername(username);
        session.setActive(true);
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        return repository.save(session);
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
    public void setReasoningLevel(UUID id, String level) {
        repository.findById(id).ifPresent(session -> {
            session.setReasoningLevel(level);
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
}