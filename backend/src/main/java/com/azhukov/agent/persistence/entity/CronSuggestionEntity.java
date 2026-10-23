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
@Table(name = "cron_suggestions")
@Data
public class CronSuggestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String source;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "job_schedule", nullable = false)
    private String jobSchedule;

    @Column(name = "job_prompt", columnDefinition = "TEXT", nullable = false)
    private String jobPrompt;

    @Column(name = "deliver_to")
    private String deliverTo;

    private String skills;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @jakarta.persistence.PrePersist
    void onCreateTimestamps() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
