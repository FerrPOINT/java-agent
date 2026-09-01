package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements {@link MidTurnPersistenceCallback} to flush new messages to the
 * database after each tool batch within a turn.
 * <p>
 * Uses {@link TransactionTemplate} for programmatic transactions (not
 * {@code @Transactional}) to avoid the self-invocation pitfall where a
 * proxy-annotated method called from the same class silently bypasses the
 * transaction proxy.
 * <p>
 * Mirrors Hermes' {@code _flush_messages_to_session_db} which tracks a
 * {@code _last_flushed_db_idx} cursor and only writes messages past that
 * point. The caller passes {@code fromIndex} as the cursor; this service
 * persists all messages from that index onward in a single transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MidTurnPersistenceService implements MidTurnPersistenceCallback {

    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;
    private final SessionRepository sessionRepository;
    private final AgentMetrics agentMetrics;

    @Override
    public boolean persistNewMessages(UUID sessionId, List<Message> messages, int fromIndex) {
        if (messages == null || messages.isEmpty() || fromIndex >= messages.size()) {
            return true; // Nothing to persist — treat as success
        }
        // Deleted-session guard: a session can be removed (user /reset, session
        // cleanup) while a turn is still streaming. Hermes' flush no-ops when
        // the session store is gone; without this guard every tool batch
        // retries the INSERT and logs an FK-violation WARN to the journal.
        if (!sessionRepository.existsById(sessionId)) {
            log.debug("Mid-turn persistence skipped: session {} no longer exists", sessionId);
            return true; // treat as flushed so the caller advances its cursor
        }
        try {
            int persistedCount = transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                java.util.List<MessageEntity> batch = new java.util.ArrayList<>();
                for (int i = fromIndex; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    // Skip system/developer messages — they are regenerated each turn
                    if (m.role() == Role.SYSTEM || m.role() == Role.DEVELOPER) continue;
                    MessageEntity e = messageMapper.toEntity(m);
                    e.setSessionId(sessionId);
                    e.setCreatedAt(now);
                    batch.add(e);
                }
                if (!batch.isEmpty()) {
                    messageRepository.saveAll(batch);
                }
                return batch.size();
            });
            log.debug("Mid-turn persistence: flushed {} messages [{}..{}] for session {}",
                persistedCount, fromIndex, messages.size() - 1, sessionId);
            // Record batch size metric
            if (agentMetrics != null && persistedCount > 0) {
                agentMetrics.recordPersistenceBatchSize(persistedCount);
            }
            // Each flush touches session metadata once using an atomic delta.
            // This avoids a COUNT(*) over the full message history and remains
            // correct when a session already has persisted messages.
            if (persistedCount > 0) {
                try {
                    transactionTemplate.execute(status -> {
                        sessionRepository.incrementMessageCount(sessionId, Instant.now(), persistedCount);
                        return null;
                    });
                } catch (Exception statEx) {
                    log.debug("Failed to update session stats after mid-turn persistence: {}", statEx.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            // M6: Don't silently swallow — return false so caller doesn't advance cursor
            log.warn("Mid-turn persistence failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }
}