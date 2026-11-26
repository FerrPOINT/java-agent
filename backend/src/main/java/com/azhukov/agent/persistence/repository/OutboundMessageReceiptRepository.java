package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboundMessageReceiptRepository extends JpaRepository<OutboundMessageReceiptEntity, UUID> {

    Optional<OutboundMessageReceiptEntity> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
        select receipt.* from outbound_message_receipts receipt
        left join sessions session on session.id = receipt.session_id
        where receipt.platform = :platform and receipt.chat_id = :chatId
          and (receipt.session_id is null or session.profile = 'default')
        order by receipt.created_at desc, receipt.id desc limit 1
        """, nativeQuery = true)
    Optional<OutboundMessageReceiptEntity> findFirstByPlatformAndChatIdOrderByCreatedAtDesc(String platform, String chatId);

    @Query(value = """
        select receipt.* from outbound_message_receipts receipt
        left join sessions session on session.id = receipt.session_id
        where receipt.platform = :platform and receipt.chat_id = :chatId and receipt.thread_id = :threadId
          and (receipt.session_id is null or session.profile = 'default')
        order by receipt.created_at desc, receipt.id desc limit 1
        """, nativeQuery = true)
    Optional<OutboundMessageReceiptEntity> findFirstByPlatformAndChatIdAndThreadIdOrderByCreatedAtDesc(String platform, String chatId, String threadId);

    List<OutboundMessageReceiptEntity> findBySessionIdOrderByCreatedAtDesc(UUID sessionId, Pageable pageable);
}
