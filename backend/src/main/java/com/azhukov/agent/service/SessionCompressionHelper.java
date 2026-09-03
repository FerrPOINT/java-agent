package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Extracted component for session compression to avoid the Spring self-invocation
 * pitfall where {@code @Retryable} and {@code @Transactional} annotations are
 * silently bypassed when a method is called from within the same class.
 * <p>
 * {@link AgentRuntimeService#compressSession} delegates to this component so
 * that the proxy-based annotations are properly engaged.
 * <p>
 * C8 fix: LLM compression call is executed OUTSIDE the transaction to avoid
 * holding a JDBC connection for 10-60+ seconds (pool starvation). The flow is:
 * 1. Read messages in a short read-only transaction
 * 2. Compress outside any transaction (LLM call)
 * 3. Persist results in a short write transaction
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCompressionHelper {

    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final ConversationCompressor conversationCompressor;
    private final ObjectProvider<SessionCompressionHelper> self;

    /**
     * Main entry point — no @Transactional here so the LLM call runs without
     * holding a DB connection. Delegates to transactional inner methods.
     */
    @Retryable(retryFor = {org.springframework.dao.OptimisticLockingFailureException.class,
                           org.springframework.dao.PessimisticLockingFailureException.class},
               maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    public void compressSessionInternal(UUID sessionId, String focusTopic, Integer keepLastN) {
        // Use self-proxy to engage @Transactional proxies on inner methods
        // (same pattern as CheckpointManager — avoids self-invocation bypass).
        SessionCompressionHelper proxy = self.getObject();
        // Record the cutoff BEFORE reading messages — any message persisted
        // after this timestamp (during the LLM compression call) is preserved.
        Instant cutoff = Instant.now();
        // 1. Read messages in a short read-only transaction
        List<Message> messages = proxy.readMessages(sessionId);
        if (messages.size() <= 4) return;

        // 2. Compress OUTSIDE any transaction (LLM call may take 10-60+ seconds)
        List<Message> compressed;
        if (keepLastN != null && keepLastN > 0) {
            compressed = conversationCompressor.compressPartial(messages, keepLastN);
        } else {
            compressed = conversationCompressor.compress(messages, focusTopic);
        }

        // 3. Persist results in a short write transaction (race-safe)
        proxy.persistCompressed(sessionId, compressed, cutoff);
    }

    /**
     * Read and map messages in a short read-only transaction.
     */
    @Transactional(readOnly = true)
    List<Message> readMessages(UUID sessionId) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return messageEntities.stream()
            .map(messageMapper::toDomain)
            .toList();
    }

    /**
     * Delete old messages and persist compressed versions in a short transaction.
     * <p>
     * Race-safe: only deletes messages that existed at the start of compression
     * (identified by a cutoff timestamp), NOT messages added during the LLM
     * compression call (10-60s window). Without this guard, a new message
     * persisted mid-compression would be deleted by {@code deleteAll(existing)}.
     */
    @Transactional
    void persistCompressed(UUID sessionId, List<Message> compressed, Instant cutoffTimestamp) {
        // Delete only messages that existed before compression started
        messageRepository.deleteBySessionIdAndCreatedAtBefore(sessionId, cutoffTimestamp);
        Instant now = Instant.now();
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            e.setSessionId(sessionId);
            e.setCreatedAt(now);
            messageRepository.save(e);
        }
    }
}