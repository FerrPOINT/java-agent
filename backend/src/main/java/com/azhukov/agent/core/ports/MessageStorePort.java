package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.MessageEntity;

import java.util.List;
import java.util.UUID;

/**
 * Persistence port (h12): message-history slice used by the agent core.
 * Implemented by the JPA {@code MessageRepository}.
 */
public interface MessageStorePort {

    List<MessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<MessageEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, int limit);

    List<MessageEntity> findBySessionIdAndActiveTrueOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionIdAndActiveTrue(UUID sessionId);

    MessageEntity save(MessageEntity entity);
}
