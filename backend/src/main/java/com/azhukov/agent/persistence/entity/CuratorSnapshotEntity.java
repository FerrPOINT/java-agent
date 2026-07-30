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
@Table(name = "curator_snapshots")
@Data
public class CuratorSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String reason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "skill_count")
    private int skillCount;

    @Column(name = "snapshot_data", columnDefinition = "TEXT")
    private String snapshotData;

    // S8: Manifest with metadata (JSON manifest with timestamp, skill count, file list)
    @Column(name = "manifest", columnDefinition = "TEXT")
    private String manifest;
}