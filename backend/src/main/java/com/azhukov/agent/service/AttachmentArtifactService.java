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

    private static final java.util.regex.Pattern SAFE_NAME =
        java.util.regex.Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    private final ObjectProvider<AttachmentArtifactRepository> repositoryProvider;
    private final ArtifactProcessIsolation artifactProcessIsolation;
    private final com.azhukov.agent.persistence.repository.SessionRepository sessions;

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

    /** Display-name normalization shared by upload and generated-file publication. */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file.bin";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("\\.{2,}", "_");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }

    /** Register inbound content; owner and session validation precede filesystem writes. */
    public ArtifactRegistration register(String ownerId, String profile, UUID sessionId,
                                         String messageId, String origin, String disposition,
                                         String mimeType, String fileName, byte[] data) {
        if (data == null || data.length == 0) {
            return new ArtifactRegistration(null, null, null, false, "attachment content is empty");
        }
        return registerStream(ownerId, profile, sessionId, messageId, origin, disposition,
            mimeType, fileName, new java.io.ByteArrayInputStream(data), true);
    }

    private ArtifactRegistration registerStream(String ownerId, String profile, UUID sessionId,
                                                String messageId, String origin, String disposition,
                                                String mimeType, String fileName,
                                                java.io.InputStream data, boolean deduplicate) {
        if (ownerId == null || ownerId.isBlank()) {
            return new ArtifactRegistration(null, null, null, false, "attachment owner required");
        }
        String selectedProfile = profile == null ? "default" : profile;
        if (!selectedProfile.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            return new ArtifactRegistration(null, null, null, false, "invalid attachment profile");
        }
        if (sessionId != null && sessions.findById(sessionId)
                .filter(session -> ownerId.equals(session.getUserId()) && selectedProfile.equals(session.getProfile()))
                .isEmpty()) {
            return new ArtifactRegistration(null, null, null, false, "session not found");
        }
        String rejection = validate(disposition, origin, fileName);
        if (rejection != null) {
            return new ArtifactRegistration(null, null, null, false, rejection);
        }
        String id = "att_" + UUID.randomUUID().toString().replace("-", "");
        String relative = ownerId + "/" + selectedProfile + "/" + id + extensionOf(fileName, mimeType);
        Path target = null;
        boolean retained = false;
        boolean created = false;
        String hash = null;
        try {
            target = safeCachePath(relative);
            Files.createDirectories(target.getParent(), privateAttributes(target.getParent(), true));
            target = safeCachePath(relative);
            ContentDigest content;
            try (var channel = Files.newByteChannel(target, java.util.Set.of(
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS), privateAttributes(target, false))) {
                created = true;
                content = copyContent(data, java.nio.channels.Channels.newOutputStream(channel));
                if (channel instanceof java.nio.channels.FileChannel file) file.force(true);
            }
            hash = content.hash();
            if (content.length() == 0) {
                return new ArtifactRegistration(null, hash, null, false, "attachment content is empty");
            }
            com.azhukov.agent.core.security.UserContext.requireCurrentAccess();
            AttachmentArtifactRepository repository = repository();
            Optional<AttachmentArtifactEntity> existing = deduplicate
                ? repository.findFirstByOwnerIdAndProfileAndContentHashAndDispositionAndSessionIdAndDedupScope(
                    ownerId, selectedProfile, hash, disposition, sessionId, "")
                : Optional.empty();
            if (existing.isPresent()) {
                AttachmentArtifactEntity artifact = existing.get();
                if (!artifact.getExpiresAt().isAfter(Instant.now())) {
                    return new ArtifactRegistration(null, hash, null, false,
                        "Matching attachment has expired; retry after attachment cleanup");
                }
                Path cached = safeCachePath(artifact.getCachePath());
                if (!Files.isRegularFile(cached, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return new ArtifactRegistration(null, hash, null, false,
                        "cached attachment content is missing or inconsistent");
                }
                try (var input = Files.newInputStream(cached, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    ContentDigest original = copyContent(input, java.io.OutputStream.nullOutputStream());
                    if (original.length() != content.length() || !original.hash().equals(hash)) {
                        return new ArtifactRegistration(null, hash, null, false,
                            "cached attachment content is missing or inconsistent");
                    }
                }
                return new ArtifactRegistration(artifact.getId(), hash, artifact.getCachePath(), true, null);
            }
            AttachmentArtifactEntity entity = new AttachmentArtifactEntity();
            entity.setId(id);
            entity.setOwnerId(ownerId);
            entity.setProfile(selectedProfile);
            entity.setSessionId(sessionId);
            entity.setMessageId(messageId);
            entity.setContentHash(hash);
            entity.setDedupScope(deduplicate ? "" : id);
            entity.setOrigin(origin);
            entity.setDisposition(disposition);
            entity.setMimeType(mimeType);
            entity.setFileName(fileName);
            entity.setSizeBytes(content.length());
            entity.setCachePath(relative);
            entity.setState("received");
            repository.save(entity);
            retained = true;
            return new ArtifactRegistration(id, hash, relative, false, null);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Attachment cache write failed: {}", e.getMessage());
            return new ArtifactRegistration(null, hash, null, false, "cache write failed: " + e.getMessage());
        } finally {
            if (created && !retained) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException e) {
                    log.warn("Could not remove unpublished attachment {}", id);
                }
            }
        }
    }

    /** Existing generated-media callers and file publication share one streaming storage path. */
    public String publishGenerated(com.azhukov.agent.core.model.Session session, String disposition,
                                   String mimeType, String fileName, byte[] bytes) {
        if (bytes == null) throw new IllegalStateException("attachment content is empty");
        return publishGenerated(session, disposition, mimeType, fileName, new java.io.ByteArrayInputStream(bytes));
    }

    /** Consumes the caller-owned stream without imposing a product file-size limit. */
    public String publishGenerated(com.azhukov.agent.core.model.Session session, String disposition,
                                   String mimeType, String fileName, java.io.InputStream content) {
        if (session == null || session.id() == null) {
            throw new IllegalArgumentException("An active session is required to publish media");
        }
        com.azhukov.agent.core.security.UserContext.requireCurrentAccess();
        var result = registerStream(session.userId(), session.profile(), session.id(), null,
            "internal", disposition, mimeType, fileName, content, false);
        if (result.rejection() != null) throw new IllegalStateException(result.rejection());
        return "MEDIA:artifact:" + result.id();
    }

    public enum OutboundStatus { UNAVAILABLE, UNCLAIMED, CLAIMED, DELIVERED }

    public OutboundStatus outboundStatus(String id, String owner, String profile, UUID sessionId) {
        return findEntity(id, owner, profile).filter(artifact -> sessionId.equals(artifact.getSessionId()))
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

    public Optional<Instant> claimOutbound(String id, String owner, String profile, UUID sessionId) {
        // The claim is read back through a timestamp column with microsecond precision.
        Instant claimedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return repository().claimOutbound(id, owner, profile, sessionId, claimedAt) == 1
            ? Optional.of(claimedAt) : Optional.empty();
    }

    public boolean releaseOutbound(String id, String owner, String profile, UUID sessionId, Instant claimedAt) {
        return repository().releaseOutbound(id, owner, profile, sessionId, claimedAt) == 1;
    }

    /** The verified descriptor is closed by the HTTP resource consumer or by the rejecting caller. */
    public record VerifiedContent(AttachmentArtifact artifact, java.io.InputStream stream)
            implements java.io.Closeable {
        @Override public void close() throws IOException { stream.close(); }
    }

    /** Verify immutable registered bytes with bounded memory and retain the same open descriptor. */
    public Optional<VerifiedContent> verifiedContent(String artifactId, String ownerId, String profile) {
        return findEntity(artifactId, ownerId, profile).flatMap(artifact -> {
            java.nio.channels.FileChannel channel = null;
            boolean transferred = false;
            try {
                Path path = safeCachePath(artifact.getCachePath());
                if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
                channel = java.nio.channels.FileChannel.open(path,
                    java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                ContentDigest content = copyContent(java.nio.channels.Channels.newInputStream(channel),
                    java.io.OutputStream.nullOutputStream());
                if (!java.util.Objects.equals(content.length(), artifact.getSizeBytes())
                        || !content.hash().equals(artifact.getContentHash())) return Optional.empty();
                channel.position(0);
                var result = new VerifiedContent(AttachmentArtifact.from(artifact),
                    java.nio.channels.Channels.newInputStream(channel));
                transferred = true;
                return Optional.of(result);
            } catch (IOException | IllegalArgumentException e) {
                return Optional.empty();
            } finally {
                if (!transferred && channel != null) {
                    try { channel.close(); }
                    catch (IOException e) { log.warn("Could not close rejected attachment content"); }
                }
            }
        });
    }

    public Optional<Path> contentPath(String artifactId, String ownerId, String profile) {
        return findEntity(artifactId, ownerId, profile).map(entity -> {
            try {
                return safeCachePath(entity.getCachePath());
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
    }

    public Optional<AttachmentArtifact> find(String artifactId, String ownerId, String profile) {
        return findEntity(artifactId, ownerId, profile).map(AttachmentArtifact::from);
    }

    public List<AttachmentArtifact> bySession(UUID sessionId, String ownerId, String profile) {
        Instant now = Instant.now();
        return repository().findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .filter(entity -> entity.getExpiresAt().isAfter(now))
            .filter(entity -> profile.equals(entity.getProfile()))
            .filter(entity -> ownerId == null || java.util.Objects.equals(ownerId, entity.getOwnerId()))
            .map(AttachmentArtifact::from).toList();
    }

    public boolean markDelivered(String artifactId, String ownerId, String profile, UUID sessionId, String messageId) {
        return repository().recordOutboundReceipt(artifactId, ownerId, profile, sessionId, messageId, Instant.now()) == 1;
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

    private String validate(String disposition, String origin, String fileName) {
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

    private record ContentDigest(long length, String hash) {}

    private static ContentDigest copyContent(java.io.InputStream input, java.io.OutputStream output)
            throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long length = new java.security.DigestInputStream(input, digest).transferTo(output);
            return new ContentDigest(length, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Optional<AttachmentArtifactEntity> findEntity(String artifactId, String ownerId, String profile) {
        Instant now = Instant.now();
        return repository().findById(artifactId)
            .filter(entity -> entity.getExpiresAt().isAfter(now))
            .filter(entity -> profile.equals(entity.getProfile()))
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
