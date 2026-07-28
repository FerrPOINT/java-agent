package com.azhukov.agent.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;

@Entity
@Table(name = "compression_locks")
@Data
public class CompressionLockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt = Instant.now();
}