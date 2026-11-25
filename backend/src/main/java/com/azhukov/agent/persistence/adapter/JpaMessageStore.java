package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.MessageStorePort;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * h12: JPA adapter implementing the core message-history port.
 */
@Repository
@RequiredArgsConstructor
public class JpaMessageStore implements MessageStorePort {

    private final MessageRepository messageRepository;

    @Override
    public List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Override
    public List<MessageEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, int limit) {
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId,
            org.springframework.data.domain.PageRequest.of(0, limit));
    }

    @Override
    public List<MessageEntity> findBySessionIdAndActiveTrueOrderByCreatedAtAsc(UUID sessionId) {
        return messageRepository.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(sessionId);
    }

    @Override
    public long countBySessionIdAndActiveTrue(UUID sessionId) {
        return messageRepository.countBySessionIdAndActiveTrue(sessionId);
    }

    @Override
    public MessageEntity save(MessageEntity entity) {
        return messageRepository.save(entity);
    }
}
