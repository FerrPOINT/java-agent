package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Durable final-delivery obligation, independent from cron and delegate lifecycles. */
@Entity
@Table(name = "delivery_work_items")
@Data
public class DeliveryWorkItemEntity {

    @Id
    private UUID id;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(nullable = false, length = 128)
    private String profile;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "parent_session_id")
    private UUID parentSessionId;

    @Column(name = "target_kind", nullable = false, length = 32)
    private String targetKind;

    @Column(length = 64)
    private String platform;

    @Column(name = "chat_id", length = 255)
    private String chatId;

    @Column(name = "thread_id", length = 255)
    private String threadId;

    @Column(name = "target_hash", nullable = false, length = 64)
    private String targetHash;

    @Column(name = "payload_text", nullable = false, columnDefinition = "TEXT")
    private String payloadText;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claim_token", length = 160)
    private String claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "idempotency_key", length = 160)
    private String idempotencyKey;

    @Column(name = "outbound_message_id", length = 255)
    private String outboundMessageId;

    @Column(name = "error_category", length = 64)
    private String errorCategory;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "dropped_at")
    private Instant droppedAt;

    @Column(name = "unknown_at")
    private Instant unknownAt;

    @PrePersist
    void defaults() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (profile == null || profile.isBlank()) profile = "default";
        if (state == null || state.isBlank()) state = "pending";
        if (availableAt == null) availableAt = now;
        if (createdAt == null) createdAt = now;
    }
}
