package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.service.ToolResultNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SessionRepository sessionRepository;

    @Retryable(retryFor = {org.springframework.dao.OptimisticLockingFailureException.class,
                           org.springframework.dao.PessimisticLockingFailureException.class},
               maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    @Transactional
    public void compressSessionInternal(UUID sessionId, String focusTopic, Integer keepLastN) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
            .stream()
            .filter(SessionCompressionHelper::isActive)
            .toList();
        if (messageEntities.size() <= 4) return;
        Set<UUID> originalIds = messageIds(messageEntities);

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

        // Hermes archive_and_compact parity: keep old rows searchable/recoverable,
        // but remove them from live context.
        List<MessageEntity> currentActive = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
            .stream()
            .filter(SessionCompressionHelper::isActive)
            .toList();
        List<MessageEntity> concurrentTail = currentActive.stream()
            .filter(entity -> entity.getId() != null && !originalIds.contains(entity.getId()))
            .toList();
        for (MessageEntity old : currentActive) {
            old.setActive(false);
            old.setCompacted(true);
        }
        messageRepository.saveAll(currentActive);
        Instant now = Instant.now();
        int sequence = 0;
        Map<String, String> toolNamesByCallId = ToolResultNameResolver.collect(compressed);
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            ToolResultNameResolver.apply(e, m, toolNamesByCallId);
            e.setSessionId(sessionId);
            e.setCreatedAt(now.plusNanos(sequence++));
            e.setActive(true);
            e.setCompacted(false);
            messageRepository.save(e);
        }
        for (MessageEntity tail : concurrentTail) {
            MessageEntity clone = cloneLiveMessage(tail);
            clone.setSessionId(sessionId);
            clone.setCreatedAt(now.plusNanos(sequence++));
            clone.setActive(true);
            clone.setCompacted(false);
            messageRepository.save(clone);
        }
        sessionRepository.updateLastActiveAndMessageCount(sessionId, now, compressed.size() + concurrentTail.size());
    }

    private static boolean isActive(MessageEntity entity) {
        return entity != null && !Boolean.FALSE.equals(entity.getActive());
    }

    private static Set<UUID> messageIds(List<MessageEntity> messages) {
        Set<UUID> ids = new HashSet<>();
        for (MessageEntity message : messages) {
            if (message != null && message.getId() != null) {
                ids.add(message.getId());
            }
        }
        return ids;
    }

    private static MessageEntity cloneLiveMessage(MessageEntity source) {
        MessageEntity clone = new MessageEntity();
        clone.setRole(source.getRole());
        clone.setContent(source.getContent());
        clone.setToolCallId(source.getToolCallId());
        clone.setToolCallName(source.getToolCallName());
        clone.setToolCallArguments(source.getToolCallArguments());
        clone.setToolCalls(source.getToolCalls());
        clone.setTurnIndex(source.getTurnIndex());
        clone.setImageCount(source.getImageCount());
        return clone;
    }
}
