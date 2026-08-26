package com.azhukov.agent.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Background job record (Hermes parity: run_in_background jobs carry
 * status PENDING/RUNNING/DONE/FAILED and a result the caller can poll).
 */
@Entity
@Table(name = "background_jobs")
@Data
public class BackgroundJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(length = 4000)
    private String prompt;

    /** PENDING | RUNNING | DONE | FAILED */
    private String status;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}