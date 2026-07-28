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
@Table(name = "checkpoints")
@Data
public class CheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String description;

    @Column(name = "file_count")
    private int fileCount;

    @Column(name = "total_size_bytes")
    private long totalSizeBytes;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "files_json", columnDefinition = "TEXT")
    private String filesJson;
}