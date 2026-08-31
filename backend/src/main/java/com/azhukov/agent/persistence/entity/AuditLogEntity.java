package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * JPA entity for the {@code audit_log} table (V11 migration).
 * Records audit events with optional user_id for multi-user attribution.
 */
@Entity
@Table(name = "audit_log")
@Data
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private String sessionId;

    private String actor;

    private String action;

    private String resource;

    @Column(columnDefinition = "TEXT")
    private String details;

    /** Multi-user: who triggered this audit event. Null = system-level. */
    @Column(name = "user_id")
    private String userId;

    @Column(name = "created_at")
    private Instant createdAt;

    @jakarta.persistence.PrePersist
    void onCreateTimestamps() {
        if (createdAt == null) createdAt = Instant.now();
    }
}