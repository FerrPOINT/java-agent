package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCompressionHelper {

    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final ConversationCompressor conversationCompressor;

    @Retryable(retryFor = {org.springframework.dao.OptimisticLockingFailureException.class,
                           org.springframework.dao.PessimisticLockingFailureException.class},
               maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    @Transactional
    public void compressSessionInternal(UUID sessionId, String focusTopic, Integer keepLastN) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messageEntities.size() <= 4) return;

        // Convert to core Message objects
        List<Message> messages = messageEntities.stream()
            .map(messageMapper::toDomain)
            .toList();

        // Use ConversationCompressor for LLM-based compression
        List<Message> compressed;
        if (keepLastN != null && keepLastN > 0) {
            compressed = conversationCompressor.compressPartial(messages, keepLastN);
        } else {
            compressed = conversationCompressor.compress(messages, focusTopic);
        }

        // Delete all old messages and persist compressed versions
        messageRepository.deleteAll(messageEntities);
        Instant now = Instant.now();
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            e.setSessionId(sessionId);
            e.setCreatedAt(now);
            messageRepository.save(e);
        }
    }
}