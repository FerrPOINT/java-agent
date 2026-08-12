package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared session resolution and creation logic used by both the streaming
 * ({@code AgentStreamingService}) and sync ({@code AgentRuntimeService}) paths.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSessionResolver {

    private final SessionRepository sessionRepository;
    private final SessionEntityMapper sessionMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * Result of {@link #resolveOrCreate(UUID, String, String)} — carries the
     * resolved/created session and whether it was newly created.
     */
    public record ResolvedSession(Session session, boolean isNew) {}

    /**
     * Resolve an existing session by ID, or create a new one when the ID is
     * {@code null} or not found in the backend database.
     *
     * @param sessionId  the requested session ID (may be {@code null})
     * @param userId     the user ID for a new session
     * @param modelName  the model name for a new session
     * @return resolved session and whether it was newly created
     */
    public ResolvedSession resolveOrCreate(UUID sessionId, String userId, String modelName) {
        if (sessionId == null) {
            return new ResolvedSession(createSession(userId, "openai-compatible", modelName), true);
        }
        try {
            return new ResolvedSession(loadSession(sessionId), false);
        } catch (IllegalArgumentException e) {
            log.warn("Session {} not found in backend, creating new session", sessionId);
            return new ResolvedSession(createSession(userId, "openai-compatible", modelName), true);
        }
    }

    /**
     * Create a new session entity and persist it.
     *
     * @param userId    the user ID
     * @param provider  the model provider
     * @param modelName the model name
     * @return the created domain session
     */
    public Session createSession(String userId, String provider, String modelName) {
        SessionEntity e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        SessionEntity saved = transactionTemplate.execute(status -> sessionRepository.save(e));
        return sessionMapper.toDomain(saved);
    }

    /**
     * Load a session by ID, hydrating CLI state metadata into the domain session.
     *
     * @param id the session ID
     * @return the domain session with hydrated metadata
     * @throws IllegalArgumentException if the session is not found
     */
    public Session loadSession(UUID id) {
        SessionEntity e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        Session session = sessionMapper.toDomain(e);
        if (e.getCliState() != null && !e.getCliState().isEmpty()) {
            for (var entry : e.getCliState().entrySet()) {
                session = session.withMetadata(entry.getKey(), entry.getValue());
            }
        }
        if (e.getSubgoal() != null && !e.getSubgoal().isBlank()) {
            session = session.withMetadata("subgoal", e.getSubgoal());
        }
        return session;
    }
}