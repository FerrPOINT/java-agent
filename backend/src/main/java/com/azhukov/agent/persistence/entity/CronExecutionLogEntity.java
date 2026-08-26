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

    /** Multi-user: owner of this execution log entry. */
    private String userId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Execution status: "success", "failure", or "timeout". */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Hermes parity: stores the job's output text for context_from chaining. */
    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    public CronExecutionLogEntity() {}

    public static CronExecutionLogEntity create(UUID jobId, Instant startedAt, Instant finishedAt, String status, String errorMessage) {
        CronExecutionLogEntity entity = new CronExecutionLogEntity();
        entity.jobId = jobId;
        entity.startedAt = startedAt;
        entity.finishedAt = finishedAt;
        entity.status = status;
        entity.errorMessage = errorMessage;
        return entity;
    }
}