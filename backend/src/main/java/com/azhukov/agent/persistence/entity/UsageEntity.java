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
@Table(name = "usage_log")
@Data
public class UsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_tokens")
    private int promptTokens;

    @Column(name = "completion_tokens")
    private int completionTokens;

    @Column(name = "total_tokens")
    private int totalTokens;

    @Column(name = "cost")
    private Double cost;

    @Column(name = "created_at")
    private Instant createdAt;

    // S10: Cache token tracking
    @Column(name = "cache_read_tokens")
    private int cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private int cacheWriteTokens;
}