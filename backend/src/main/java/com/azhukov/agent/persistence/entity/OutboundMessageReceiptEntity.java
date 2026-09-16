package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound platform message receipt (ADR-012). General cross-cutting store of
 * outbound message ids per target — powers {@code react}/{@code unreact}
 * without an explicit message id and gives every outbound send path a
 * durable trace. Not a second delivery ledger: {@code delivery_work_items}
 * remains the sole cron/delegate delivery lane (WP-1 cutover).
 */
@Entity
@Table(name = "outbound_message_receipts")
@Data
public class OutboundMessageReceiptEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "chat_id", nullable = false, length = 255)
    private String chatId;

    @Column(name = "thread_id", length = 255)
    private String threadId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(nullable = false, length = 32)
    private String direction;

    @Column(name = "message_id", nullable = false, length = 255)
    private String messageId;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
