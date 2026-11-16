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
        assertThat(service.outboundStatus(registration.id(), "u1", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNCLAIMED);
        assertThat(service.outboundStatus(registration.id(), "u1", UUID.randomUUID()))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNAVAILABLE);
        assertThat(service.outboundStatus(registration.id(), "u2", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.UNAVAILABLE);
        stored.getValue().setOutboundClaimedAt(java.time.Instant.now());
        assertThat(service.outboundStatus(registration.id(), "u1", session))
            .isEqualTo(AttachmentArtifactService.OutboundStatus.CLAIMED);
        assertThat(service.verifiedContent(registration.id(), "u1")).hasValueSatisfying(
            content -> assertThat(content.bytes()).isEqualTo(bytes));
        Files.write(tempDir.resolve(registration.cachePath()), new byte[]{3, 2, 1});
        assertThat(service.verifiedContent(registration.id(), "u1")).isEmpty();
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
        when(repository.findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope(
            org.mockito.ArgumentMatchers.eq("u1"), any(), org.mockito.ArgumentMatchers.eq("photo"), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("")))
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
    void unsafeNamesAndOversizeRejected() {
        var service = service();
        byte[] data = "x".getBytes();

        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "../escape.txt", data).rejection()).contains("not safe");
        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "weird name!", data).rejection()).contains("not safe");
        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "a.txt", new byte[0]).rejection()).contains("empty");
        byte[] huge = new byte[(int) (AttachmentArtifactService.MAX_ATTACHMENT_BYTES + 1)];
        assertThat(service.register("u1", "default", null, null, "cli", "document",
            "text/plain", "big.bin", huge).rejection()).contains("exceeds");
        verify(repository, never()).save(any());
    }

    @Test
    void receiptUsesExactOwnerAndSession() {
        var session = UUID.randomUUID();
        when(repository.recordOutboundReceipt(eq("att_1"), eq("user-a"), eq(session), eq("42"), any(Instant.class)))
            .thenReturn(1);
        var service = service();

        assertThat(service.markDelivered("att_1", "user-a", session, "42")).isTrue();
        assertThat(service.markDelivered("att_1", "user-b", session, "42")).isFalse();
        assertThat(service.markDelivered("att_1", "user-a", UUID.randomUUID(), "42")).isFalse();
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

        assertThat(Files.readAllBytes(service().contentPath(stored.id(), "u1").orElseThrow()))
            .isEqualTo(data);
        assertThat(service().contentPath(stored.id(), "other-owner")).isEmpty();
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
        when(repository.findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("")))
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

}
