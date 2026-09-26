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
        AttachmentArtifactService service = new AttachmentArtifactService(prov(repository));
        ReflectionTestUtils.setField(service, "cacheRootConfig", tempDir.toString());
        lenient().when(repository.save(any(AttachmentArtifactEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        return service;
    }

    @Test
    void registerWritesBlobAndMetadata() {
        byte[] data = "hello attachment".getBytes();
        var result = service().register("u1", "default", UUID.randomUUID(), "m1",
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
        when(repository.findFirstByOwnerIdAndContentHashAndDisposition(
            org.mockito.ArgumentMatchers.eq("u1"), any(), org.mockito.ArgumentMatchers.eq("photo")))
            .thenReturn(Optional.of(existing));

        var result = service().register("u1", "default", null, null,
            "telegram", "photo", "image/png", "p.png", data);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.id()).isEqualTo("att_existing");
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
    void ownerScopedLookupAndReceiptDoNotCrossArtifactBoundary() {
        AttachmentArtifactEntity artifact = new AttachmentArtifactEntity();
        artifact.setId("att_private");
        artifact.setOwnerId("user-b");
        artifact.setState("received");
        when(repository.findById("att_private")).thenReturn(Optional.of(artifact));

        assertThat(service().find("att_private", "user-a")).isEmpty();
        assertThat(service().markDelivered("att_private", "user-a", "msg-1")).isFalse();
        assertThat(artifact.getState()).isEqualTo("received");
        verify(repository, never()).save(artifact);
    }

    @Test
    void markDeliveredIsIdempotent() {
        AttachmentArtifactEntity delivered = new AttachmentArtifactEntity();
        delivered.setId("att_1");
        delivered.setState("delivered");
        when(repository.findById("att_1")).thenReturn(Optional.of(delivered));

        assertThat(service().markDelivered("att_1")).isTrue();
        verify(repository, never()).save(any()); // already delivered — no rewrite
    }

    @Test
    void markDeliveredTransitionsReceivedOnce() {
        AttachmentArtifactEntity received = new AttachmentArtifactEntity();
        received.setId("att_2");
        received.setState("received");
        when(repository.findById("att_2")).thenReturn(Optional.of(received));

        assertThat(service().markDelivered("att_2")).isTrue();
        assertThat(received.getState()).isEqualTo("delivered");
        verify(repository).save(received);
    }

    @Test
    void unknownArtifactMarkDeliveredFalse() {
        when(repository.findById("att_missing")).thenReturn(Optional.empty());
        assertThat(service().markDelivered("att_missing")).isFalse();
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
        when(repository.deleteExpired(any(Instant.class))).thenReturn(1);

        int removed = service().sweepExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(blob).doesNotExist();
        verify(repository).deleteExpired(any(Instant.class));
    }

    @Test
    void sessionDeleteCascadesToArtifacts() {
        UUID sessionId = UUID.randomUUID();
        Path blob = tempDir.resolve("att_session.bin");
        try {
            Files.writeString(blob, "data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AttachmentArtifactEntity artifact = entity("att-1", sessionId, Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(artifact, "cachePath", "att_session.bin");
        when(repository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(artifact));

        int removed = service().cleanupForSession(sessionId);

        assertThat(removed).isEqualTo(1);
        assertThat(blob).doesNotExist();
        verify(repository).deleteAllById(List.of("att-1"));
    }

    @Test
    void sessionDeleteListenerSwallowsFailures() {
        UUID sessionId = UUID.randomUUID();
        when(repository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenThrow(new IllegalStateException("db gone"));

        // Must not throw — session deletion must never be blocked by cleanup.
        service().onSessionDeleted(new com.azhukov.agent.core.agent.SessionDeletedEvent(sessionId));

        verify(repository, never()).deleteAllById(any());
    }
}
