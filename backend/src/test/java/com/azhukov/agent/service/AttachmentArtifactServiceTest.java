package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.AttachmentArtifactEntity;
import com.azhukov.agent.persistence.repository.AttachmentArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-11: attachment artifact contract — validation, hash dedupe, safe cache
 * paths, idempotent delivered receipts, TTL cleanup.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentArtifactServiceTest {

    @Mock
    private AttachmentArtifactRepository repository;

    @Mock
    private com.azhukov.agent.persistence.repository.SessionRepository sessions;

    private UUID sessionOwnedBy(String owner) {
        var entity = new com.azhukov.agent.persistence.entity.SessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(owner);
        lenient().when(sessions.findById(entity.getId())).thenReturn(Optional.of(entity));
        return entity.getId();
    }

    @TempDir
    Path tempDir;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private AttachmentArtifactService service() {
        AttachmentArtifactService service = new AttachmentArtifactService(prov(repository), new ArtifactProcessIsolation(), sessions);
        ReflectionTestUtils.setField(service, "cacheRootConfig", tempDir.toString());
        lenient().when(repository.save(any(AttachmentArtifactEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        return service;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"owner-a", "owner-b"})
    void registrationRejectsForeignOrUnknownSessionBeforeWritingAnything(String owner) throws Exception {
        var service = service();
        UUID foreign = sessionOwnedBy("other-owner");
        for (UUID id : List.of(foreign, UUID.randomUUID())) {
            var result = service.register(owner, "default", id, null, "cli", "document",
                "text/plain", "note.txt", "private bytes".getBytes());
            assertThat(result.id()).isNull();
            assertThat(result.rejection()).isEqualTo("session not found");
        }
        org.mockito.Mockito.verifyNoInteractions(repository);
        try (var files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {" "})
    void registrationRequiresAnExplicitOwner(String owner) throws Exception {
        var result = service().register(owner, "default", null, null, "cli", "document",
            "text/plain", "note.txt", "bytes".getBytes());
        assertThat(result.id()).isNull();
        assertThat(result.rejection()).isEqualTo("attachment owner required");
        org.mockito.Mockito.verifyNoInteractions(repository);
        try (var files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"owner-a", "owner-b"})
    void uploadUsesAuthenticatedOwnerAndRejectsForeignSession(String owner) throws Exception {
        var service = service();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.azhukov.agent.api.AttachmentController(prov(service))).build();
        var file = new org.springframework.mock.web.MockMultipartFile("file", "note.txt", "text/plain", "bytes".getBytes());
        com.azhukov.agent.core.security.UserContext.set(owner, com.azhukov.agent.core.security.UserContext.ROLE_USER);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/v1/attachments").file(file).param("ownerId", "other-owner")
                    .param("sessionId", sessionOwnedBy("other-owner").toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            org.mockito.Mockito.verifyNoInteractions(repository);
            UUID ownSession = sessionOwnedBy(owner);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/v1/attachments").file(file).param("ownerId", "other-owner")
                    .param("sessionId", ownSession.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            var saved = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getOwnerId()).isEqualTo(owner);
            assertThat(saved.getValue().getSessionId()).isEqualTo(ownSession);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
        }
    }

    @Test
    void sameOwnerCanRegisterIdenticalBytesSeparatelyAndReadOnlyTheSelectedProfile() throws Exception {
        var service = service();
        byte[] bytes = "profile attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var alpha = service.register("shared-owner", "alpha", null, null, "internal", "file", "text/plain", "a.txt", bytes);
        var beta = service.register("shared-owner", "beta", null, null, "internal", "file", "text/plain", "b.txt", bytes);
        assertThat(alpha.rejection()).isNull();
        assertThat(beta.rejection()).isNull();
        assertThat(alpha.id()).isNotEqualTo(beta.id());
        assertThat(alpha.cachePath()).startsWith("shared-owner/alpha/");
        assertThat(beta.cachePath()).startsWith("shared-owner/beta/");
        var captor = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        var records = captor.getAllValues().stream().collect(java.util.stream.Collectors.toMap(
            AttachmentArtifactEntity::getId, java.util.function.Function.identity()));
        when(repository.findById(alpha.id())).thenReturn(Optional.of(records.get(alpha.id())));
        when(repository.findById(beta.id())).thenReturn(Optional.of(records.get(beta.id())));
        assertThat(service.contentPath(alpha.id(), "shared-owner", "beta")).isEmpty();
        assertThat(service.contentPath(alpha.id(), "shared-owner", "alpha")).isPresent();
        assertThat(service.contentPath(beta.id(), "shared-owner", "beta")).isPresent();
    }

    @Test
    void uploadWithoutExplicitOwnerUsesAuthenticatedAdministrator() throws Exception {
        var service = service();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(new com.azhukov.agent.api.AttachmentController(prov(service))).build();
        var file = new org.springframework.mock.web.MockMultipartFile("file", "note.txt", "text/plain", "bytes".getBytes());
        com.azhukov.agent.core.security.UserContext.set("admin-owner", com.azhukov.agent.core.security.UserContext.ROLE_ADMIN);
        try {
            UUID ownSession = sessionOwnedBy("admin-owner");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .multipart("/api/v1/attachments").file(file).param("sessionId", ownSession.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            var saved = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getOwnerId()).isEqualTo("admin-owner");
            assertThat(saved.getValue().getCachePath()).startsWith("admin-owner/");
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
        }
    }

    @Test
    void scopedOutboundContentDoesNotServeModifiedBytes() throws Exception {
        var service = service();
        var session = sessionOwnedBy("u1");
        byte[] bytes = {1, 2, 3};
        var registration = service.register("u1", "default", session, null,
            "internal", "document", "application/octet-stream", "example.bin", bytes);
        var stored = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
        verify(repository).save(stored.capture());
        when(repository.findById(registration.id())).thenReturn(Optional.of(stored.getValue()));
        assertThat(service.outboundStatus(registration.id(), "u1", "default", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNCLAIMED);
        assertThat(service.outboundStatus(registration.id(), "u1", "default", UUID.randomUUID()))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNAVAILABLE);
        assertThat(service.outboundStatus(registration.id(), "u2", "default", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNAVAILABLE);
        stored.getValue().setOutboundClaimedAt(java.time.Instant.now());
        assertThat(service.outboundStatus(registration.id(), "u1", "default", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.CLAIMED);
        try (var content = service.verifiedContent(registration.id(), "u1", "default").orElseThrow()) {
            assertThat(content.stream().readAllBytes()).isEqualTo(bytes);
        }
        Files.write(tempDir.resolve(registration.cachePath()), new byte[]{3, 2, 1});
        assertThat(service.verifiedContent(registration.id(), "u1", "default")).isEmpty();
    }

    @Test
    void registerWritesBlobAndMetadata() {
        byte[] data = "hello attachment".getBytes();
        var result = service().register("u1", "default", sessionOwnedBy("u1"), "m1",
            "telegram", "document", "text/plain", "note.txt", data);

        assertThat(result.rejection()).isNull();
        assertThat(result.duplicate()).isFalse();
        assertThat(result.id()).startsWith("att_");
        assertThat(result.contentHash()).hasSize(64);
        assertThat(tempDir.resolve(result.cachePath())).exists();
        verify(repository).save(any(AttachmentArtifactEntity.class));
    }

    @Test
    void duplicateOwnerHashDispositionIsOneArtifact() throws Exception {
        byte[] data = "same bytes".getBytes();
        AttachmentArtifactEntity existing = new AttachmentArtifactEntity();
        existing.setId("att_existing");
        existing.setOwnerId("u1");
        existing.setContentHash(java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)));
        existing.setDisposition("photo");
        existing.setCachePath("u1/att_existing.png");
        Files.createDirectories(tempDir.resolve("u1"));
        Files.write(tempDir.resolve("u1/att_existing.png"), data);
        when(repository.findFirstByOwnerIdAndProfileAndContentHashAndDispositionAndSessionIdAndDedupScope(
            org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("default"), any(), org.mockito.ArgumentMatchers.eq("photo"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("")))
            .thenReturn(Optional.of(existing));

        var service = service();
        var result = service.register("u1", "default", null, null,
            "telegram", "photo", "image/png", "p.png", data);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.id()).isEqualTo("att_existing");

        existing.setExpiresAt(Instant.now().minusSeconds(1));
        var expired = service.register("u1", "default", null, null,
            "telegram", "photo", "image/png", "p.png", data);
        assertThat(expired.id()).isNull();
        assertThat(expired.rejection()).contains("expired");
        assertThat(tempDir.resolve(existing.getCachePath())).exists();
        verify(repository, never()).save(any());
    }

    @Test
    void unsafeNamesAndEmptyContentAreRejected() {
        var service = service();
        byte[] data = "x".getBytes();

        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "../escape.txt", data).rejection()).contains("not safe");
        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "weird name!", data).rejection()).contains("not safe");
        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "a.txt", new byte[0]).rejection()).contains("empty");
        verify(repository, never()).save(any());
    }

    @Test
    void receiptUsesExactOwnerAndSession() {
        var session = UUID.randomUUID();
        when(repository.recordOutboundReceipt(eq("att_1"), eq("user-a"), org.mockito.ArgumentMatchers.eq("default"), eq(session), eq("42"), any(Instant.class)))
            .thenReturn(1);
        var service = service();

        assertThat(service.markDelivered("att_1", "user-a", "default", session, "42")).isTrue();
        assertThat(service.markDelivered("att_1", "user-b", "default", session, "42")).isFalse();
        assertThat(service.markDelivered("att_1", "user-a", "default", UUID.randomUUID(), "42")).isFalse();
    }

    // helper to expose hash for dedupe stubbing
    @SuppressWarnings("unused")
    private static class HashProbe {
        static String sha256(AttachmentArtifactService service, byte[] data)
            throws Exception {
            var method = AttachmentArtifactService.class.getDeclaredMethod("sha256", byte[].class);
            method.setAccessible(true);
            return (String) method.invoke(null, data);
        }
    }

    private static AttachmentArtifactEntity entity(String id, UUID sessionId, Instant expiresAt) {
        AttachmentArtifactEntity entity = new AttachmentArtifactEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "sessionId", sessionId);
        ReflectionTestUtils.setField(entity, "expiresAt", expiresAt);
        return entity;
    }

    @Test
    void sweepExpiredCoversDeliveredArtifactsToo() {
        // WP-4 tail: the old sweep selected only state="received" — delivered
        // artifacts (with receipts) expired on paper but their blobs sat on
        // disk forever. The sweep must cover ANY state.
        Path blob = tempDir.resolve("att_expired_delivered.bin");
        try {
            Files.writeString(blob, "data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AttachmentArtifactEntity delivered = entity("expired-delivered", null, Instant.now().minusSeconds(60));
        ReflectionTestUtils.setField(delivered, "state", "delivered");
        ReflectionTestUtils.setField(delivered, "cachePath", "att_expired_delivered.bin");
        when(repository.findByExpiresAtBefore(any(Instant.class))).thenReturn(List.of(delivered));
        when(repository.deleteExpiredByIds(eq(List.of(delivered.getId())), any(Instant.class))).thenReturn(1);

        int removed = service().sweepExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(blob).doesNotExist();
        verify(repository).deleteExpiredByIds(eq(List.of(delivered.getId())), any(Instant.class));
    }

    @Test
    void sessionDeletionLeavesBytesUntilCommitAndKeepsFailedCleanupForRetry() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Path blob = Files.writeString(tempDir.resolve("att_session.bin"), "data");
        Path blocked = Files.createDirectory(tempDir.resolve("blocked"));
        AttachmentArtifactEntity expired = entity("att-1", sessionId, Instant.now().minusSeconds(1));
        AttachmentArtifactEntity failed = entity("att-2", sessionId, Instant.now().minusSeconds(1));
        ReflectionTestUtils.setField(expired, "cachePath", "att_session.bin");
        ReflectionTestUtils.setField(failed, "cachePath", "blocked");
        when(repository.findByExpiresAtBefore(any(Instant.class))).thenReturn(List.of(expired, failed));
        var service = service();
        var event = new com.azhukov.agent.core.agent.SessionDeletedEvent(sessionId);

        service.expireDeletedSessionArtifacts(event);
        assertThat(blob).exists();
        verify(repository).expireForSession(eq(sessionId), any(Instant.class));

        service.scheduledSweep();
        assertThat(blob).doesNotExist();
        assertThat(blocked).exists();
        verify(repository).deleteExpiredByIds(eq(List.of("att-1")), any(Instant.class));
    }

    @Test
    void configuredCacheSurvivesServiceRecreation() throws Exception {
        byte[] data = "persist across process configuration".getBytes();
        var stored = service().register("u1", "default", null, null,
            "cli", "document", "text/plain", "durable.txt", data);
        var entity = new AttachmentArtifactEntity();
        entity.setId(stored.id());
        entity.setOwnerId("u1");
        entity.setCachePath(stored.cachePath());
        when(repository.findById(stored.id())).thenReturn(Optional.of(entity));

        assertThat(Files.readAllBytes(service().contentPath(stored.id(), "u1", "default").orElseThrow()))
            .isEqualTo(data);
        assertThat(service().contentPath(stored.id(), "other-owner", "default")).isEmpty();
    }

    @Test
    void parentSymlinkCannotWriteOutsideCache() throws Exception {
        Path outside = Files.createTempDirectory("attachment-outside-");
        try {
            Files.createSymbolicLink(tempDir.resolve("u1"), outside);
            var result = service().register("u1", "default", null, null,
                "cli", "document", "text/plain", "safe.txt", "bytes".getBytes());
            assertThat(result.rejection()).isNotNull();
            try (var files = Files.list(outside)) {
                assertThat(files).isEmpty();
            }
            verify(repository, never()).save(any());
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void missingDuplicateBlobCannotReportSuccessfulRegistration() {
        var entity = new AttachmentArtifactEntity();
        entity.setId("att_missing");
        entity.setCachePath("u1/missing.txt");
        when(repository.findFirstByOwnerIdAndProfileAndContentHashAndDispositionAndSessionIdAndDedupScope(any(), org.mockito.ArgumentMatchers.eq("default"), any(), any(), any(), org.mockito.ArgumentMatchers.eq("")))
            .thenReturn(Optional.of(entity));
        var result = service().register("u1", "default", null, null,
            "cli", "document", "text/plain", "safe.txt", "bytes".getBytes());
        assertThat(result.rejection()).contains("missing or inconsistent");
        assertThat(result.id()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void legitimateNestedOwnerAndDotsRemainUsableWithPrivateFiles() throws Exception {
        var result = service().register("team/a..b", "default", null, null,
            "cli", "document", "text/plain", "safe.txt", "bytes".getBytes());
        assertThat(result.rejection()).isNull();
        Path file = tempDir.resolve(result.cachePath());
        assertThat(Files.readString(file)).isEqualTo("bytes");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(file.getParent()))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void explicitConfiguredRootMayUseALegitimateFilesystemAlias() throws Exception {
        Path actual = tempDir.resolve("actual");
        Files.createDirectory(actual);
        Path alias = tempDir.resolve("alias");
        Files.createSymbolicLink(alias, actual);
        var service = service();
        ReflectionTestUtils.setField(service, "cacheRootConfig", alias.resolve("attachments").toString());
        var result = service.register("u1", "default", null, null,
            "cli", "document", "text/plain", "safe.txt", "bytes".getBytes());
        assertThat(result.rejection()).isNull();
        assertThat(actual.resolve("attachments").resolve(result.cachePath())).hasContent("bytes");
        verify(repository).save(any());
    }

    @Test
    void unavailableConfiguredRootDoesNotFallBackToTemporaryStorage() throws Exception {
        Path blocked = tempDir.resolve("not-a-directory");
        Files.writeString(blocked, "occupied");
        var service = service();
        ReflectionTestUtils.setField(service, "cacheRootConfig", blocked.toString());
        var result = service.register("u1", "default", null, null,
            "cli", "document", "text/plain", "safe.txt", "bytes".getBytes());
        assertThat(result.rejection()).contains("cache write failed");
        verify(repository, never()).save(any());
        assertThat(Files.readString(blocked)).isEqualTo("occupied");
    }

    @Test
    void registrationAboveFormerCapPreservesExactContentAndOwner() throws Exception {
        byte[] data = new byte[21 * 1024 * 1024];
        data[data.length - 1] = 91;
        var service = service();
        var result = service.register("owner-a", "default", null, null, "internal", "document",
            "application/octet-stream", "large.bin", data);
        assertThat(result.rejection()).isNull();
        var saved = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("owner-a");
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(data.length);
        assertThat(saved.getValue().getContentHash()).isEqualTo(java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(data)));
        assertThat(Files.readAllBytes(tempDir.resolve(result.cachePath()))).isEqualTo(data);
    }

    @Test
    void verifiedContentPinsTheOriginalDescriptorAndClosesAfterReading() throws Exception {
        var service = service();
        byte[] original = "accepted immutable artifact".getBytes();
        var registration = service.register("u1", "default", null, null, "internal", "document",
            "text/plain", "note.txt", original);
        var saved = org.mockito.ArgumentCaptor.forClass(AttachmentArtifactEntity.class);
        verify(repository).save(saved.capture());
        saved.getValue().setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findById(registration.id())).thenReturn(Optional.of(saved.getValue()));
        Path cached = tempDir.resolve(registration.cachePath());
        try (var verified = service.verifiedContent(registration.id(), "u1", "default").orElseThrow()) {
            Path replacement = Files.writeString(tempDir.resolve("replacement"), "different bytes");
            Files.move(replacement, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertThat(verified.stream().readAllBytes()).isEqualTo(original);
        }
        assertThat(service.verifiedContent(registration.id(), "u1", "default")).isEmpty();
        assertThat(service.verifiedContent(registration.id(), "another-owner", "default")).isEmpty();
    }

    @Test
    void failedStreamAndMetadataPublicationLeaveNoNewBlob() throws Exception {
        var service = service();
        var session = com.azhukov.agent.core.model.Session.create("u1", "noop", "test");
        var entity = new com.azhukov.agent.persistence.entity.SessionEntity();
        entity.setId(session.id());
        entity.setUserId("u1");
        when(sessions.findById(session.id())).thenReturn(Optional.of(entity));
        var failing = new java.io.InputStream() {
            int calls;
            @Override public int read() throws java.io.IOException {
                if (calls++ == 0) return 1;
                throw new java.io.IOException("source read failed");
            }
        };
        assertThatThrownBy(() -> service.publishGenerated(session, "document", "application/octet-stream",
            "report.bin", failing)).isInstanceOf(IllegalStateException.class).hasMessageContaining("source read failed");
        verify(repository, never()).save(any());
        when(repository.save(any())).thenThrow(new IllegalStateException("metadata write failed"));
        assertThatThrownBy(() -> service.register("u1", "default", null, null, "internal", "document",
            "text/plain", "note.txt", "bytes".getBytes())).hasMessage("metadata write failed");
        try (var files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

}
