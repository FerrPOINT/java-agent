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
    /** Multi-user: owner of this cron job. */
    private String userId;

    /** Hermes profile scope that owns this cron job. */
    @Column(name = "profile", nullable = false)
    private String profile = "default";

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

    /** Hermes monitor source: http(s) URL or script path under ~/.hermes/scripts/. */
    @Column(name = "monitor")
    private String monitor;

    @Column(name = "monitor_last_hash")
    private String monitorLastHash;

    @Column(name = "monitor_last_output", columnDefinition = "TEXT")
    private String monitorLastOutput;

    @Column(name = "monitor_last_changed_at")
    private Instant monitorLastChangedAt;

    /** Hermes continuity flag; also mirrored into context_from=self for model-facing parity. */
    @Column(name = "continuity_enabled")
    private boolean continuityEnabled = false;

    /** Session explicitly attached via cronjob(attach_to_session=true). */
    @Column(name = "attached_session_id")
    private UUID attachedSessionId;

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

    /** Global provider captured for unpinned agent jobs at create/update time. */
    @Column(name = "provider_snapshot")
    private String providerSnapshot;

    /** Global model captured for unpinned agent jobs at create/update time. */
    @Column(name = "model_snapshot")
    private String modelSnapshot;

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

    // h75/h76: Session produced by the last run (delivery reads its output) and the
    // high-water mark the bot-side delivery poller has already delivered through.
    @Column(name = "last_run_session_id")
    private UUID lastRunSessionId;

    @Column(name = "last_delivered_run_at")
    private Instant lastDeliveredRunAt;

    @jakarta.persistence.PrePersist
    void onCreateTimestamps() {
        if (createdAt == null) createdAt = java.time.Instant.now();
    }
}
