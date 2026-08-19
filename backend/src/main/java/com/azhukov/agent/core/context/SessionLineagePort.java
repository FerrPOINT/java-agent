package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;

import java.util.List;
import java.util.UUID;

/**
 * Port interface for loading session lineage messages (root-to-tip across
 * compression-rotated ancestor sessions). Declared in {@code core.context}
 * so that {@link DefaultContextEngine} can depend on it without importing
 * from {@code core.agent}, breaking the {@code core.agent ↔ core.context}
 * circular dependency.
 * <p>
 * Implementations live in {@code core.agent} (e.g.
 * {@code com.azhukov.agent.core.agent.SessionLineageService}) and are
 * injected into {@code DefaultContextEngine} by the Spring context.
 *
 * @see DefaultContextEngine#setSessionLineageService(SessionLineagePort)
 */
public interface SessionLineagePort {

    /**
     * Load messages from the entire session lineage (root to tip), combining
     * messages from all ancestor sessions with the current session's messages.
     * <p>
     * Mirrors Hermes {@code get_messages_as_conversation(session_id, include_ancestors=True)}.
     *
     * @param sessionId the current (tip) session ID
     * @return combined message list from all sessions in the lineage, ordered root-to-tip;
     *         empty list if {@code sessionId} is null or has no messages
     */
    List<Message> loadMessagesWithAncestors(UUID sessionId);
}