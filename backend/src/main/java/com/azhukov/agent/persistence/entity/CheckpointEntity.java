package com.azhukov.agent.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "checkpoints")
@Data
public class CheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Multi-user: owner of this checkpoint. */
    private String userId;

    private String description;

    @Column(name = "file_count")
    private int fileCount;

    @Column(name = "total_size_bytes")
    private long totalSizeBytes;

    @Column(name = "created_at")
    private Instant createdAt;

    @JsonIgnore
    @Column(name = "files_json", columnDefinition = "TEXT")
    private String filesJson;

    @JsonIgnore
    @OneToMany(mappedBy = "checkpoint", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CheckpointFileEntity> files = new ArrayList<>();
}