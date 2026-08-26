package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.context.SessionLineagePort;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Session lineage walker — ported from Hermes {@code _session_lineage_root_to_tip()}.
 * <p>
 * After compression rotation, the old session's messages stay in the parent session
 * and the child session starts fresh. When loading the child session, this service
 * walks the parent→child chain (root-to-tip) and loads messages from all ancestor
 * sessions, mirroring Hermes {@code get_messages_as_conversation(include_ancestors=True)}.
 * <p>
 * The lineage chain is built by walking UP from the given session to the root parent,
 * then reversed to produce an ordered list [root_parent, ..., parent, current_session].
 * Messages from ancestor sessions are prepended as prior context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionLineageService implements SessionLineagePort {

    /** Maximum chain depth to guard against accidental cycles. Mirrors Hermes cap of 100. */
    static final int MAX_CHAIN_DEPTH = 100;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * Walk the parent→child chain from the given session UP to the root parent,
     * collecting all session IDs. Returns an ordered list:
     * [root_parent, ..., parent, current_session].
     * <p>
     * Ported from Hermes {@code _session_lineage_root_to_tip(session_id)}.
     *
     * @param sessionId the starting session ID (typically the current/tip session)
     * @return ordered list of session IDs from root to tip, or [sessionId] if no parents
     */
    public List<UUID> findAncestorSessionIds(UUID sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        return transactionTemplate.execute(status -> {
            List<UUID> chain = new ArrayList<>();
            UUID current = sessionId;
            Set<UUID> seen = new HashSet<>();
            for (int i = 0; i < MAX_CHAIN_DEPTH; i++) {
                if (current == null || seen.contains(current)) {
                    break;
                }
                seen.add(current);
                chain.add(current);
                SessionEntity entity = sessionRepository.findById(current).orElse(null);
                if (entity == null) {
                    break;
                }
                current = entity.getParentSessionId();
            }
            // Reverse to get root-to-tip order
            Collections.reverse(chain);
            return chain;
        });
    }

    /**
     * Check if a session has a parent session (i.e., it was created via compression rotation).
     *
     * @param sessionId the session ID to check
     * @return true if the session has a parentSessionId, false otherwise
     */
    public boolean hasParentSession(UUID sessionId) {
        if (sessionId == null) {
            return false;
        }
        return transactionTemplate.execute(status -> {
            SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
            return entity != null && entity.getParentSessionId() != null;
        });
    }

    /**
     * Load messages from the entire session lineage (root to tip), combining messages
     * from all ancestor sessions with the current session's messages.
     * <p>
     * Mirrors Hermes {@code get_messages_as_conversation(session_id, include_ancestors=True)}.
     * <p>
     * For ancestor sessions, messages are loaded and added as prior context at the
     * beginning of the list. The current session's messages are appended at the end.
     * This ensures the agent has historical context after compression rotation.
     *
     * @param sessionId the current (tip) session ID
     * @return combined message list from all sessions in the lineage, ordered root-to-tip
     */
    @Override
    public List<Message> loadMessagesWithAncestors(UUID sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        List<UUID> lineage = findAncestorSessionIds(sessionId);
        if (lineage.isEmpty() || (lineage.size() == 1 && lineage.get(0).equals(sessionId))) {
            // No ancestors — just load current session messages
            return loadMessagesForSession(sessionId);
        }

        log.debug("Loading messages with ancestors for session {}: lineage size={}", sessionId, lineage.size());

        List<Message> combined = new ArrayList<>();
        for (UUID sessionInChain : lineage) {
            List<Message> sessionMessages = loadMessagesForSession(sessionInChain);
            if (!sessionMessages.isEmpty()) {
                combined.addAll(sessionMessages);
            }
        }
        return combined;
    }

    /**
     * Load messages for a single session, mapped to domain Message objects.
     *
     * @param sessionId the session ID
     * @return list of messages in ascending order by creation time
     */
    private List<Message> loadMessagesForSession(UUID sessionId) {
        // P2 parity: ancestors of a compression-rotated session have their raw rows
        // deactivated (active=false, compacted=true) at rotation time; their content
        // lives on as the compaction summary in the child session. Load active rows
        // only, so the rebuilt context reflects the post-compaction transcript.
        List<MessageEntity> entities = messageRepository.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(sessionId);
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> messages = new ArrayList<>(entities.size());
        for (MessageEntity entity : entities) {
            Message msg = messageMapper.toDomain(entity);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }
}