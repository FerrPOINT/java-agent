package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Execute-code session kernel metadata/lease (WP-7, V60). Never a process handle. */
@Entity
@Table(name = "code_session_kernels")
@Data
public class CodeSessionKernelEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "kernel_id", nullable = false, length = 64)
    private String kernelId;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    @Column(name = "user_id", length = 128)
    private String userId;

    /** alive | lost | expired | killed. */
    @Column(name = "state", nullable = false, length = 16)
    private String state = "alive";

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
