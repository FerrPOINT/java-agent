package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Per-profile runtime registry row (ADR-013, WP-4). */
@Entity
@Table(name = "profile_runtime_state")
@Data
public class ProfileRuntimeStateEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "profile", nullable = false, unique = true, length = 64)
    private String profile;

    @Column(name = "config_revision", nullable = false)
    private long configRevision;

    @Column(name = "tool_registry_revision", nullable = false)
    private long toolRegistryRevision;

    @Column(name = "skill_revision", nullable = false)
    private long skillRevision;

    /** unbound | bound:<platform> — WP-2 home channel binding state. */
    @Column(name = "gateway_state", nullable = false, length = 32)
    private String gatewayState = "unbound";

    /** running | draining | stopped | failed. */
    @Column(name = "worker_state", nullable = false, length = 32)
    private String workerState = "stopped";

    @Column(name = "last_reload_status", length = 32)
    private String lastReloadStatus;

    @Column(name = "last_reload_error", columnDefinition = "TEXT")
    private String lastReloadError;

    @Column(name = "last_reload_at")
    private Instant lastReloadAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
