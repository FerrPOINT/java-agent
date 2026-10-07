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
@Table(name = "memory_write_audit")
@Data
public class MemoryWriteAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private String action;

    @Column(name = "memory_id")
    private UUID memoryId;

    @Column(columnDefinition = "TEXT")
    private String fact;

    @Column(name = "old_fact", columnDefinition = "TEXT")
    private String oldFact;

    @Column(name = "write_origin")
    private String writeOrigin;

    @Column(name = "execution_context")
    private String executionContext;

    @Column(name = "source_session_id")
    private String sourceSessionId;

    @Column(name = "parent_session_id")
    private String parentSessionId;

    private String platform;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "created_at")
    private Instant createdAt;

    @jakarta.persistence.PrePersist
    void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
