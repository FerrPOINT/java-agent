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

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_run_at")
    private Instant nextRunAt;
}