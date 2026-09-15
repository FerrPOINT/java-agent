package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Attachment artifact metadata (WP-11, V62). Blobs stay in cache roots. */
@Entity
@Table(name = "attachment_artifacts")
@Data
public class AttachmentArtifactEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId = "system";

    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "message_id", length = 128)
    private String messageId;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    /** telegram | cli | internal. */
    @Column(name = "origin", nullable = false, length = 16)
    private String origin;

    /** photo | document | video | audio | voice | sticker | location | file. */
    @Column(name = "disposition", nullable = false, length = 16)
    private String disposition;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Relative to the controlled cache root — never an absolute arbitrary path. */
    @Column(name = "cache_path", columnDefinition = "TEXT")
    private String cachePath;

    /** received | delivered | failed | expired. */
    @Column(name = "state", nullable = false, length = 16)
    private String state = "received";

    /** V65: platform message id of the successful outbound delivery, if any. */
    @Column(name = "delivered_message_id", length = 128)
    private String deliveredMessageId;

    /** V65: when the outbound delivery happened. */
    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "lifetime_secs", nullable = false)
    private int lifetimeSecs = 86_400;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(86_400);
}
