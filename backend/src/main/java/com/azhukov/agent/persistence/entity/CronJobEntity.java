package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cron_jobs")
@Data
public class CronJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    private String schedule;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    private boolean enabled = true;

    @Column(name = "deliver_to")
    private String deliverTo;

    // S17: Per-job skill loading — comma-separated skill names
    @Column(name = "skills")
    private String skills;

    /** P1-45: Comma-separated upstream cron job IDs whose output should be injected as context. */
    @Column(name = "context_from")
    private String contextFrom;

    // ── V26: Full Hermes parity fields ──

    /** Repeat count: null = forever, N = run N times then auto-delete. */
    @Column(name = "repeat_count")
    private Integer repeatCount;

    /** How many times the job has successfully completed. */
    @Column(name = "repeat_completed")
    private int repeatCompleted = 0;

    /** Path to a script for script-only (no_agent) mode or data-collection. */
    @Column(name = "script")
    private String script;

    /** Skip the LLM entirely — run script and deliver stdout verbatim. */
    @Column(name = "no_agent")
    private boolean noAgent = false;

    /** Comma-separated toolset names to restrict the job's agent to. */
    @Column(name = "enabled_toolsets")
    private String enabledToolsets;

    /** Working directory for the job's agent. */
    @Column(name = "workdir")
    private String workdir;

    /** Per-job model provider override. */
    @Column(name = "model_provider")
    private String modelProvider;

    /** Per-job model name override. */
    @Column(name = "model_name")
    private String modelName;

    /** Per-job base URL override. */
    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    // h71/h74: Track last execution status to detect stale error states and
    // prevent permanent error from blocking future executions.
    @Column(name = "last_status")
    private String lastStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    // h74: Consecutive failure count for backoff during backend unavailability.
    @Column(name = "consecutive_failures")
    private int consecutiveFailures = 0;
}