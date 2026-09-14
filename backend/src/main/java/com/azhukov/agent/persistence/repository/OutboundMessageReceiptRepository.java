package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundMessageReceiptRepository extends JpaRepository<OutboundMessageReceiptEntity, UUID> {

    Optional<OutboundMessageReceiptEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<OutboundMessageReceiptEntity> findFirstByPlatformAndChatIdOrderByCreatedAtDesc(String platform, String chatId);

    Optional<OutboundMessageReceiptEntity> findFirstByPlatformAndChatIdAndThreadIdOrderByCreatedAtDesc(String platform, String chatId, String threadId);

    List<OutboundMessageReceiptEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);
}
