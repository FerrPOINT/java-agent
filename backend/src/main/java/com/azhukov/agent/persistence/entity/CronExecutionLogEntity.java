package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * h72: Cron execution ledger — records each cron job execution.
 * <p>
 * Each execution records: job_id, started_at, finished_at, status
 * (success/failure/timeout), error_message.
 */
@Entity
@Table(name = "cron_execution_log")
@Data
public class CronExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Execution status: "success", "failure", or "timeout". */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    public CronExecutionLogEntity() {}

    public CronExecutionLogEntity(UUID jobId, Instant startedAt, Instant finishedAt, String status, String errorMessage) {
        this.jobId = jobId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.errorMessage = errorMessage;
    }
}