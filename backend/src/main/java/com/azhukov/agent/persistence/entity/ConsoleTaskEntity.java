package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Durable console command task (WP-9, V61, ADR-015). */
@Entity
@Table(name = "console_tasks")
@Data
public class ConsoleTaskEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    @Column(name = "user_id", length = 128)
    private String userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "command", nullable = false, columnDefinition = "TEXT")
    private String command;

    @Column(name = "workdir", columnDefinition = "TEXT")
    private String workdir;

    /** running | completed | failed | cancelled | timeout. */
    @Column(name = "state", nullable = false, length = 16)
    private String state = "running";

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 60;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(24 * 3600);
}
