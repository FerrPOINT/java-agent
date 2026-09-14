package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Whitelisted dashboard action ledger row (ADR-013, WP-4). */
@Entity
@Table(name = "dashboard_actions")
@Data
public class DashboardActionEntity {

    @Id
    @UuidGenerator
    private UUID id;

    /** doctor | prompt-size | dump | security-audit | config-migrate | backup | checkpoint-prune. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "profile", length = 64)
    private String profile;

    /** pending | running | completed | failed | cancelled. */
    @Column(name = "state", nullable = false, length = 32)
    private String state = "pending";

    @Column(name = "output_path", columnDefinition = "TEXT")
    private String outputPath;

    @Column(name = "output_sha256", length = 64)
    private String outputSha256;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
