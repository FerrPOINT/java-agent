package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory_pending")
@Data
public class PendingMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String userId;
    private String action;
    private String target;
    private String content;
    private String oldText;
    private String summary;
    private String origin;
    private String status = "pending";
    private Instant createdAt;
    private Instant resolvedAt;

    @jakarta.persistence.PrePersist
    void onCreateTimestamps() {
        if (createdAt == null) createdAt = java.time.Instant.now();
    }
}
