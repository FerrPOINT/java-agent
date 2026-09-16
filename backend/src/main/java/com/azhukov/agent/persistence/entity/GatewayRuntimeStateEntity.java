package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Durable gateway lifecycle state per profile (ADR-012): RUNNING / DRAINING /
 * STOPPED / FAILED. Dashboard status reads survive restarts; in-memory
 * {@code GatewayLifecycleService} is the runtime authority, this row is the
 * persisted view.
 */
@Entity
@Table(name = "gateway_runtime_state")
@Data
public class GatewayRuntimeStateEntity {

    @Id
    @Column(nullable = false, length = 128)
    private String profile;

    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
