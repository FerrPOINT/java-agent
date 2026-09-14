package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Durable OpenAI Runs state machine row (WP-6, V59). */
@Entity
@Table(name = "openai_run_states")
@Data
public class OpenAiRunStateEntity {

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "session_id", nullable = false)
    private java.util.UUID sessionId;

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    @Column(name = "external_run_id", length = 128)
    private String externalRunId;

    @Column(name = "model", length = 128)
    private String model;

    /** queued | in_progress | requires_action | completed | failed | cancelled | expired. */
    @Column(name = "state", nullable = false, length = 20)
    private String state;

    @Column(name = "state_reason", columnDefinition = "TEXT")
    private String stateReason;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
