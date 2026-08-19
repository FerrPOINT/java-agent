package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory")
@Data
public class MemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String userId;

    private String category;

    private String fact;

    private String target = "memory";

    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * JPA optimistic lock version — detects concurrent modifications from other
     * sessions, tools, or manual DB edits. Equivalent to Hermes' file-based
     * _detect_external_drift() round-trip mismatch detection (issue #26045).
     */
    @Version
    private Long version;

    @jakarta.persistence.PrePersist
    void onCreateTimestamps() {
        if (createdAt == null) createdAt = java.time.Instant.now();
        if (updatedAt == null) updatedAt = java.time.Instant.now();
    }
}
