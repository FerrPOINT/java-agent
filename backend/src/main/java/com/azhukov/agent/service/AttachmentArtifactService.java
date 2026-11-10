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
import java.util.ArrayList;
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
    private final ArtifactProcessIsolation artifactProcessIsolation;

    @Value("${agent.attachments.cache-root:}")
    private String cacheRootConfig;

    private Path cacheRoot;

    private Path cacheRoot() {
        if (cacheRoot == null) {
            Path configured = (cacheRootConfig == null || cacheRootConfig.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "agent-attachments")
                : Path.of(cacheRootConfig);
            configured = configured.toAbsolutePath().normalize();
            try {
                Files.createDirectories(configured, privateAttributes(configured, true));
                // The explicitly configured root may itself be a legitimate filesystem alias.
                // Pin its real directory; untrusted relative artifact paths cannot redirect it.
                cacheRoot = configured.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("configured attachment cache is unavailable", e);
            }
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
        return register(ownerId, profile, sessionId, messageId, origin, disposition, mimeType, fileName, data, true);
    }

    private ArtifactRegistration register(String ownerId, String profile, UUID sessionId,
                                          String messageId, String origin, String disposition,
                                          String mimeType, String fileName, byte[] data, boolean deduplicate) {
        String rejection = validate(disposition, origin, fileName, data);
        if (rejection != null) {
            return new ArtifactRegistration(null, null, null, false, rejection);
        }
        String hash = sha256(data);
        AttachmentArtifactRepository repository = repository();
        Optional<AttachmentArtifactEntity> existing = deduplicate
            ? repository.findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope(
                ownerId, hash, disposition, sessionId, "")
            : Optional.empty();
        if (existing.isPresent()) {
            AttachmentArtifactEntity artifact = existing.get();
            if (!artifact.getExpiresAt().isAfter(Instant.now())) {
                return new ArtifactRegistration(null, hash, null, false,
                    "Matching attachment has expired; retry after attachment cleanup");
            }
            try {
                Path cached = safeCachePath(artifact.getCachePath());
                if (!Files.isRegularFile(cached, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(cached) != data.length
                    || !sha256(Files.readAllBytes(cached)).equals(hash)) {
                    return new ArtifactRegistration(null, hash, null, false,
                        "cached attachment content is missing or inconsistent");
                }
                return new ArtifactRegistration(artifact.getId(), hash, artifact.getCachePath(), true, null);
            } catch (IOException | IllegalArgumentException e) {
                return new ArtifactRegistration(null, hash, null, false,
                    "cached attachment content is unavailable");
            }
        }
        String id = "att_" + UUID.randomUUID().toString().replace("-", "");
        String relative = ownerId + "/" + id + extensionOf(fileName, mimeType);
        try {
            Path target = safeCachePath(relative);
            Files.createDirectories(target.getParent(), privateAttributes(target.getParent(), true));
            // Recheck newly created parents before creating a unique artifact file.
            target = safeCachePath(relative);
            try (var channel = Files.newByteChannel(target, java.util.Set.of(
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS), privateAttributes(target, false))) {
                var buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (channel instanceof java.nio.channels.FileChannel file) {
                    file.force(true);
                }
            }
            AttachmentArtifactEntity entity = new AttachmentArtifactEntity();
            entity.setId(id);
            entity.setOwnerId(ownerId == null || ownerId.isBlank() ? "system" : ownerId);
            entity.setProfile(profile == null || profile.isBlank() ? "default" : profile);
            entity.setSessionId(sessionId);
            entity.setMessageId(messageId);
            entity.setContentHash(hash);
            entity.setDedupScope(deduplicate ? "" : id);
            entity.setOrigin(origin);
            entity.setDisposition(disposition);
            entity.setMimeType(mimeType);
            entity.setFileName(fileName);
            entity.setSizeBytes((long) data.length);
            entity.setCachePath(relative);
            entity.setState("received");
            repository.save(entity);
            return new ArtifactRegistration(id, hash, relative, false, null);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Attachment cache write failed: {}", e.getMessage());
            return new ArtifactRegistration(null, hash, null, false,
                "cache write failed: " + e.getMessage());
        }
    }

    /** Publish generated bytes in the current turn. The caller never supplies a path to read. */
    public String publishGenerated(com.azhukov.agent.core.model.Session session, String disposition,
                                   String mimeType, String fileName, byte[] bytes) {
        if (session == null || session.id() == null) {
            throw new IllegalArgumentException("An active session is required to publish media");
        }
        var result = register(session.userId(), session.getMetadata("profile"), session.id(), null,
            "internal", disposition, mimeType, fileName, bytes, false);
        if (result.rejection() != null) {
            throw new IllegalStateException(result.rejection());
        }
        return "MEDIA:artifact:" + result.id();
    }

    public enum OutboundStatus { UNAVAILABLE, UNCLAIMED, CLAIMED, DELIVERED }

    public OutboundStatus outboundStatus(String id, String owner, UUID sessionId) {
        return findEntity(id, owner).filter(artifact -> sessionId.equals(artifact.getSessionId()))
            .filter(artifact -> artifact.getExpiresAt().isAfter(Instant.now()))
            .filter(artifact -> "received".equals(artifact.getState()) || "delivered".equals(artifact.getState()))
            .map(artifact -> {
                if ("delivered".equals(artifact.getState())) return OutboundStatus.DELIVERED;
                return artifact.getOutboundClaimedAt() == null ? OutboundStatus.UNCLAIMED : OutboundStatus.CLAIMED;
            })
            .orElse(OutboundStatus.UNAVAILABLE);
    }

    public boolean hasProtectedOutbound() {
        try {
            return artifactProcessIsolation.protects(cacheRoot());
        } catch (IllegalArgumentException e) {
            log.warn("Outbound artifact storage is unavailable");
            return false;
        }
    }

    public Optional<Instant> claimOutbound(String id, String owner, UUID sessionId) {
        // The claim is read back through a timestamp column with microsecond precision.
        Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return repository().claimOutbound(id, owner, sessionId, claimedAt) == 1
            ? Optional.of(claimedAt) : Optional.empty();
    }

    public boolean releaseOutbound(String id, String owner, UUID sessionId, Instant claimedAt) {
        return repository().releaseOutbound(id, owner, sessionId, claimedAt) == 1;
    }

    public record VerifiedContent(AttachmentArtifact artifact, byte[] bytes) {}

    /** Read the exact registered payload before serving it to an outbound consumer. */
    public Optional<VerifiedContent> verifiedContent(String artifactId, String ownerId) {
        return findEntity(artifactId, ownerId).flatMap(artifact -> {
            try {
                Path path = safeCachePath(artifact.getCachePath());
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
                try (var input = Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes = input.readNBytes((int) MAX_ATTACHMENT_BYTES + 1);
                    if (bytes.length > MAX_ATTACHMENT_BYTES || !java.util.Objects.equals((long) bytes.length, artifact.getSizeBytes())
                            || !sha256(bytes).equals(artifact.getContentHash())) return Optional.empty();
                    return Optional.of(new VerifiedContent(AttachmentArtifact.from(artifact), bytes));
                }
            } catch (IOException | IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    public Optional<Path> contentPath(String artifactId, String ownerId) {
        return findEntity(artifactId, ownerId).map(entity -> {
            try {
                return safeCachePath(entity.getCachePath());
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    /** Backward-compatible admin/service lookup without an owner filter. */
    public Optional<Path> contentPath(String artifactId) {
        return contentPath(artifactId, null);
    }

    public Optional<AttachmentArtifact> find(String artifactId, String ownerId) {
        return findEntity(artifactId, ownerId).map(AttachmentArtifact::from);
    }

    /** Backward-compatible admin/service lookup without an owner filter. */
    public Optional<AttachmentArtifact> find(String artifactId) {
        return find(artifactId, null);
    }

    public List<AttachmentArtifact> bySession(UUID sessionId, String ownerId) {
        Instant now = Instant.now();
        return repository().findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .filter(entity -> entity.getExpiresAt().isAfter(now))
            .filter(entity -> ownerId == null || java.util.Objects.equals(ownerId, entity.getOwnerId()))
            .map(AttachmentArtifact::from).toList();
    }

    /** Backward-compatible admin/service lookup without an owner filter. */
    public List<AttachmentArtifact> bySession(UUID sessionId) {
        return bySession(sessionId, null);
    }

    public boolean markDelivered(String artifactId, String ownerId, UUID sessionId, String messageId) {
        return repository().recordOutboundReceipt(artifactId, ownerId, sessionId, messageId, Instant.now()) == 1;
    }

    /**
     * TTL cleanup of expired metadata rows + cache blobs (any state —
     * delivered artifacts expire too; a receipt row without its blob is a
     * lie, so files go first, rows after).
     */
    @org.springframework.transaction.annotation.Transactional
    public int sweepExpired() {
        return sweepExpiredWithinTransaction();
    }

    /** Internal body shared by direct calls and the scheduler's proxy entry point. */
    private int sweepExpiredWithinTransaction() {
        Instant cutoff = Instant.now();
        return removeExpired(repository().findByExpiresAtBefore(cutoff), cutoff);
    }

    private int removeExpired(List<AttachmentArtifactEntity> candidates, Instant cutoff) {
        List<String> removed = new ArrayList<>();
        for (AttachmentArtifactEntity entity : candidates) {
            try {
                if (entity.getCachePath() != null) {
                    Path path = safeCachePath(entity.getCachePath());
                    if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Attachment blob is a directory");
                    }
                    Files.deleteIfExists(path);
                }
                removed.add(entity.getId());
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                log.warn("Attachment blob cleanup failed for {}", entity.getId());
            }
        }
        int deleted = 0;
        for (int from = 0; from < removed.size(); from += 500) {
            deleted += repository().deleteExpiredByIds(removed.subList(from, Math.min(from + 500, removed.size())), cutoff);
        }
        return deleted;
    }

    @org.springframework.context.event.EventListener
    public void expireDeletedSessionArtifacts(com.azhukov.agent.core.agent.SessionDeletedEvent event) {
        repository().expireForSession(event.sessionId(), Instant.now());
    }

    /** Scheduled wrapper so the TTL sweep actually runs (WP-4 tail: the method existed with zero callers). */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    @org.springframework.transaction.annotation.Transactional
    public void scheduledSweep() {
        try {
            int removed = sweepExpiredWithinTransaction();
            if (removed > 0) {
                log.info("Attachment TTL sweep removed {} expired artifact(s)", removed);
            }
        } catch (Exception e) {
            log.warn("Attachment TTL sweep failed: {}", e.getMessage());
        }
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
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("unsafe cache path");
        }
        Path suffix = Path.of(relative);
        if (suffix.isAbsolute()) {
            throw new IllegalArgumentException("absolute cache path");
        }
        for (Path part : suffix) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException("parent cache path");
            }
        }
        Path root = cacheRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(suffix).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("cache path escapes root");
        }
        Path current = root.getRoot();
        for (Path part : root) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("cache root contains a symlink");
            }
        }
        for (Path part : root.relativize(resolved)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("cache path contains a symlink");
            }
        }
        return resolved;
    }

    private static java.nio.file.attribute.FileAttribute<?>[] privateAttributes(Path path, boolean directory)
        throws IOException {
        Path existing = path;
        while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS) && existing.getParent() != null) {
            existing = existing.getParent();
        }
        if (Files.getFileStore(existing).supportsFileAttributeView("posix")) {
            return new java.nio.file.attribute.FileAttribute<?>[] {
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
            };
        }
        return new java.nio.file.attribute.FileAttribute<?>[0];
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

    private Optional<AttachmentArtifactEntity> findEntity(String artifactId, String ownerId) {
        Instant now = Instant.now();
        return repository().findById(artifactId)
            .filter(entity -> entity.getExpiresAt().isAfter(now))
            .filter(entity -> ownerId == null || java.util.Objects.equals(ownerId, entity.getOwnerId()));
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
