package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Persisted home channel for a platform+profile (Hermes {@code HomeChannel}
 * parity, ADR-012). Authority for bare-platform delivery targets: {@code
 * deliver="telegram"} resolves through this row, not the legacy
 * first-allowed-user-id heuristic.
 */
@Entity
@Table(name = "gateway_home_channels")
@Data
@IdClass(GatewayHomeChannelEntity.GatewayHomeChannelId.class)
public class GatewayHomeChannelEntity {

    @Id
    @Column(nullable = false, length = 64)
    private String platform;

    @Id
    @Column(nullable = false, length = 128)
    private String profile;

    @Column(name = "chat_id", nullable = false, length = 255)
    private String chatId;

    @Column(name = "thread_id", length = 255)
    private String threadId;

    @Column(length = 255)
    private String name;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Data
    public static class GatewayHomeChannelId implements Serializable {
        private String platform;
        private String profile;
    }
}
