package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.UUID;

/**
 * Stores per-file content for a checkpoint, enabling actual file restoration on rollback.
 * Content is stored as Base64-encoded bytes to support binary files in TEXT columns.
 */
@Entity
@Table(name = "checkpoint_files", uniqueConstraints = {
    @UniqueConstraint(name = "uk_checkpoint_file_path", columnNames = {"checkpoint_id", "file_path"})
})
@Data
public class CheckpointFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkpoint_id", nullable = false)
    @JsonIgnore
    private CheckpointEntity checkpoint;

    @Column(name = "file_path", nullable = false, length = 4096)
    private String filePath;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** Base64-encoded file content. Nullable for files that exceeded the content size limit. */
    @Column(name = "content_base64", columnDefinition = "TEXT")
    private String contentBase64;
}