package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.AttachmentArtifactEntity;
import com.azhukov.agent.persistence.repository.AttachmentArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WP-11 (docs/35): cross-surface attachment artifact contract.
 *
 * <p>Telegram inbound and CLI drops convert into {@link AttachmentArtifact}
 * BEFORE any chat request. Persistence stores metadata + a SAFE cache
 * reference (relative to the controlled cache root, validated — no
 * arbitrary absolute paths, no symlink escapes, no blobs in the DB). Hash
 * dedupe: the same owner+content+disposition is one artifact. Outbound
 * receipts reference artifact ids so ambiguous-success retries never send
 * the same artifact twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentArtifactService {

    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    private static final java.util.regex.Pattern SAFE_NAME =
        java.util.regex.Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    private final ObjectProvider<AttachmentArtifactRepository> repositoryProvider;

    @Value("${agent.attachments.cache-root:}")
    private String cacheRootConfig;

    private Path cacheRoot;

    private Path cacheRoot() {
        if (cacheRoot == null) {
            cacheRoot = (cacheRootConfig == null || cacheRootConfig.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "agent-attachments")
                : Path.of(cacheRootConfig);
        }
        return cacheRoot;
    }

    public record ArtifactRegistration(String id, String contentHash, String cachePath,
                                       boolean duplicate, String rejection) {}

    public record AttachmentArtifact(String id, String ownerId, String profile, UUID sessionId,
                                     String messageId, String contentHash, String origin,
                                     String disposition, String mimeType, String fileName,
                                     long sizeBytes, String state,
                                     String deliveredMessageId, Instant deliveredAt) {

        public static AttachmentArtifact from(AttachmentArtifactEntity entity) {
            return new AttachmentArtifact(entity.getId(), entity.getOwnerId(), entity.getProfile(),
                entity.getSessionId(), entity.getMessageId(), entity.getContentHash(),
                entity.getOrigin(), entity.getDisposition(), entity.getMimeType(),
                entity.getFileName(), entity.getSizeBytes() == null ? 0 : entity.getSizeBytes(),
                entity.getState(), entity.getDeliveredMessageId(), entity.getDeliveredAt());
        }
    }

    /** Register an inbound artifact (dedupe by owner+hash+disposition). */
    public ArtifactRegistration register(String ownerId, String profile, UUID sessionId,
                                         String messageId, String origin, String disposition,
                                         String mimeType, String fileName, byte[] data) {
        String rejection = validate(disposition, origin, fileName, data);
        if (rejection != null) {
            return new ArtifactRegistration(null, null, null, false, rejection);
        }
        String hash = sha256(data);
        AttachmentArtifactRepository repository = repository();
        Optional<AttachmentArtifactEntity> existing = repository
            .findFirstByOwnerIdAndContentHashAndDisposition(ownerId, hash, disposition);
        if (existing.isPresent()) {
            return new ArtifactRegistration(existing.get().getId(), hash,
                existing.get().getCachePath(), true, null);
        }
        String id = "att_" + UUID.randomUUID().toString().replace("-", "");
        String relative = ownerId + "/" + id + extensionOf(fileName, mimeType);
        try {
            Path target = safeCachePath(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            AttachmentArtifactEntity entity = new AttachmentArtifactEntity();
            entity.setId(id);
            entity.setOwnerId(ownerId == null || ownerId.isBlank() ? "system" : ownerId);
            entity.setProfile(profile == null || profile.isBlank() ? "default" : profile);
            entity.setSessionId(sessionId);
            entity.setMessageId(messageId);
            entity.setContentHash(hash);
            entity.setOrigin(origin);
            entity.setDisposition(disposition);
            entity.setMimeType(mimeType);
            entity.setFileName(fileName);
            entity.setSizeBytes((long) data.length);
            entity.setCachePath(relative);
            entity.setState("received");
            repository.save(entity);
            return new ArtifactRegistration(id, hash, relative, false, null);
        } catch (IOException e) {
            log.warn("Attachment cache write failed: {}", e.getMessage());
            return new ArtifactRegistration(null, hash, null, false,
                "cache write failed: " + e.getMessage());
        }
    }

    /** Resolve a stored artifact's absolute cache path (validated). */
    public Optional<Path> contentPath(String artifactId) {
        return repository().findById(artifactId).map(entity -> {
            try {
                return safeCachePath(entity.getCachePath());
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    public Optional<AttachmentArtifact> find(String artifactId) {
        return repository().findById(artifactId).map(AttachmentArtifact::from);
    }

    public List<AttachmentArtifact> bySession(UUID sessionId) {
        return repository().findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .map(AttachmentArtifact::from).toList();
    }

    /** Outbound receipt: mark delivered exactly once (idempotent). */
    public boolean markDelivered(String artifactId) {
        return markDelivered(artifactId, null);
    }

    /**
     * Outbound receipt with the platform message id of the successful send.
     * Idempotent: an already-delivered artifact keeps its FIRST receipt —
     * a retry after an ambiguous send never overwrites it and the caller
     * can detect the duplicate by the false return.
     */
    public boolean markDelivered(String artifactId, String deliveredMessageId) {
        AttachmentArtifactRepository repository = repository();
        AttachmentArtifactEntity entity = repository.findById(artifactId).orElse(null);
        if (entity == null) {
            return false;
        }
        if ("delivered".equals(entity.getState())) {
            return true;
        }
        entity.setState("delivered");
        if (deliveredMessageId != null && !deliveredMessageId.isBlank()) {
            entity.setDeliveredMessageId(deliveredMessageId.trim());
            entity.setDeliveredAt(Instant.now());
        }
        repository.save(entity);
        return true;
    }

    /** TTL cleanup of expired metadata rows + cache blobs. */
    public int sweepExpired() {
        AttachmentArtifactRepository repository = repository();
        List<AttachmentArtifactEntity> expired = repository
            .findByStateAndExpiresAtBefore("received", Instant.now());
        for (AttachmentArtifactEntity entity : expired) {
            try {
                if (entity.getCachePath() != null) {
                    Files.deleteIfExists(safeCachePath(entity.getCachePath()));
                }
            } catch (Exception e) {
                log.debug("Attachment blob cleanup failed for {}: {}",
                    entity.getId(), e.getMessage());
            }
        }
        return repository.deleteExpired(Instant.now());
    }

    // ── internals ────────────────────────────────────────────────────────

    private String validate(String disposition, String origin, String fileName, byte[] data) {
        if (data == null || data.length == 0) {
            return "attachment content is empty";
        }
        if (data.length > MAX_ATTACHMENT_BYTES) {
            return "attachment exceeds " + MAX_ATTACHMENT_BYTES + " bytes";
        }
        if (disposition == null || disposition.isBlank()) {
            return "disposition is required";
        }
        if (origin == null || origin.isBlank()) {
            return "origin is required";
        }
        if (fileName != null && !fileName.isBlank() && !SAFE_NAME.matcher(fileName).matches()) {
            return "file name is not safe (allowed: letters, digits, dot, underscore, dash)";
        }
        return null;
    }

    /** Resolve a relative cache path INSIDE the cache root; reject escapes. */
    private Path safeCachePath(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/")
            || relative.contains("..")) {
            throw new IllegalArgumentException("unsafe cache path");
        }
        Path root = cacheRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("cache path escapes root");
        }
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("cache path is a symlink");
        }
        return resolved;
    }

    private static String extensionOf(String fileName, String mimeType) {
        if (fileName != null && fileName.lastIndexOf('.') > 0) {
            return fileName.substring(fileName.lastIndexOf('.'));
        }
        return switch (mimeType == null ? "" : mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "audio/ogg" -> ".ogg";
            case "audio/mpeg" -> ".mp3";
            case "video/mp4" -> ".mp4";
            case "application/pdf" -> ".pdf";
            default -> ".bin";
        };
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AttachmentArtifactRepository repository() {
        AttachmentArtifactRepository repository = repositoryProvider == null
            ? null : repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("attachment repository is unavailable");
        }
        return repository;
    }
}
