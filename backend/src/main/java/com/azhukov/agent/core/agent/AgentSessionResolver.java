package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.azhukov.agent.config.AgentProperties;

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
    private final MessageRepository messageRepository;
    private final SessionLineageService sessionLineageService;

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
            // P0-24: Follow compression child chain to find the descendant with messages
            UUID resolvedId = resolveResumeSessionId(sessionId);
            if (!resolvedId.equals(sessionId)) {
                log.info("Session {} redirected to child {} (compression chain resolution)", sessionId, resolvedId);
            }
            return new ResolvedSession(loadSession(resolvedId), false);
        } catch (IllegalArgumentException e) {
            // Fail-open by design (bot keeps its own bot_sessions table; the
            // backend session may legitimately not exist yet) — not a fault,
            // keep it out of the WARN noise that journal forensics scans.
            log.info("Session {} not found in backend, creating new session", sessionId);
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
        return createSession(userId, provider, modelName, "cli");
    }

    /**
     * Create a new session with a specific source (e.g. "telegram", "cli", "api_server").
     * The source becomes the platform in the system prompt volatile tier.
     */
    public Session createSession(String userId, String provider, String modelName, String source) {
        SessionEntity e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setSource(source);
        e.setLastActive(Instant.now());
        e.setMessageCount(0);
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
        // Wrap the load + cliState access in a read-only transaction so the
        // lazy ElementCollection is initialized while the Hibernate session
        // is still open. Without this, getCliState() triggers a
        // LazyInitializationException when called from the async streaming
        // thread (outside any transaction).
        return transactionTemplate.execute(status -> {
            SessionEntity e = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
            // Force-initialize the lazy cliState collection inside the tx
            Map<String, String> cliStateCopy;
            if (Hibernate.isInitialized(e.getCliState())) {
                cliStateCopy = new HashMap<>(e.getCliState());
            } else {
                Hibernate.initialize(e.getCliState());
                cliStateCopy = new HashMap<>(e.getCliState());
            }
            Session session = sessionMapper.toDomain(e);
            for (var entry : cliStateCopy.entrySet()) {
                session = session.withMetadata(entry.getKey(), entry.getValue());
            }
            // Add source as platform metadata so the system prompt builder
            // can include "Platform: telegram" in the volatile tier.
            if (e.getSource() != null && !e.getSource().isBlank()) {
                session = session.withMetadata("platform", e.getSource());
            }
            // Add userId as userDisplayName so the system prompt can include "User: ...".
            if (e.getUserId() != null && !e.getUserId().isBlank() && !AgentProperties.DEFAULT_USER_ID.equals(e.getUserId())
                    && (session.metadata() == null || !session.metadata().containsKey("userDisplayName"))) {
                session = session.withMetadata("userDisplayName", e.getUserId());
            }
            if (e.getSubgoal() != null && !e.getSubgoal().isBlank()) {
                session = session.withMetadata("subgoal", e.getSubgoal());
            }
            return session;
        });
    }

    /**
     * P0-24: Resolve a session ID to the descendant that actually holds messages.
     * <p>
     * Context compression ends the current session and forks a new child session
     * (linked via {@code parentSessionId}). The child is where new messages land —
     * the parent ends up with 0 messages. This method walks the parent → child chain
     * and returns the first descendant that has at least one message.
     * <p>
     * Ported from Hermes {@code resolve_resume_session_id()}.
     * <p>
     * The chain is walked via the most recently created child; depth cap (32) guards
     * against accidental loops.
     *
     * @param sessionId the requested session ID
     * @return the resolved session ID (same as input if no redirect needed)
     */
    public UUID resolveResumeSessionId(UUID sessionId) {
        if (sessionId == null) {
            return sessionId;
        }
        return transactionTemplate.execute(status -> {
            // If this session still has ACTIVE messages, nothing to redirect.
            // Hermes parity: rotation archives ancestor rows (active=false);
            // counting ALL rows (incl. archived) makes the resolver treat the
            // superseded parent as "has messages" and the next bot turn keeps
            // writing into the dead parent while the compacted child is ignored
            // (live 2026-08-27: parent stayed active, child 'New chat (compressed)'
            // accumulated a second transcript copy).
            if (messageRepository.countBySessionIdAndActiveTrue(sessionId) > 0) {
                return sessionId;
            }
            // Walk descendants: at each step, pick the most-recently-created child
            UUID current = sessionId;
            Set<UUID> seen = new HashSet<>();
            seen.add(current);
            for (int i = 0; i < 32; i++) {
                List<SessionEntity> children = sessionRepository
                    .findByParentSessionIdOrderByCreatedAtDesc(current);
                if (children == null || children.isEmpty()) {
                    return sessionId;
                }
                UUID childId = children.get(0).getId();
                if (childId == null || seen.contains(childId)) {
                    return sessionId;
                }
                seen.add(childId);
                // Check if this child has active messages
                if (messageRepository.countBySessionIdAndActiveTrue(childId) > 0) {
                    return childId;
                }
                current = childId;
            }
            return sessionId;
        });
    }

    /**
     * Walk the parent→child chain from the given session UP to the root parent,
     * collecting all ancestor session IDs. Returns an ordered list:
     * [root_parent, ..., parent, current_session].
     * <p>
     * Delegates to {@link SessionLineageService#findAncestorSessionIds(UUID)}.
     * Ported from Hermes {@code _session_lineage_root_to_tip(session_id)}.
     *
     * @param sessionId the starting session ID (typically the current/tip session)
     * @return ordered list of session IDs from root to tip, or [sessionId] if no parents
     */
    public List<UUID> findAncestorSessionIds(UUID sessionId) {
        return sessionLineageService.findAncestorSessionIds(sessionId);
    }

    /**
     * Check if a session has a parent session (i.e., it was created via compression rotation).
     * <p>
     * Delegates to {@link SessionLineageService#hasParentSession(UUID)}.
     *
     * @param sessionId the session ID to check
     * @return true if the session has a parentSessionId, false otherwise
     */
    public boolean hasParentSession(UUID sessionId) {
        return sessionLineageService.hasParentSession(sessionId);
    }

    /**
     * Load messages from the entire session lineage (root to tip), combining messages
     * from all ancestor sessions with the current session's messages.
     * <p>
     * Delegates to {@link SessionLineageService#loadMessagesWithAncestors(UUID)}.
     * Mirrors Hermes {@code get_messages_as_conversation(session_id, include_ancestors=True)}.
     *
     * @param sessionId the current (tip) session ID
     * @return combined message list from all sessions in the lineage, ordered root-to-tip
     */
    public List<com.azhukov.agent.core.model.Message> loadMessagesWithAncestors(UUID sessionId) {
        return sessionLineageService.loadMessagesWithAncestors(sessionId);
    }
}