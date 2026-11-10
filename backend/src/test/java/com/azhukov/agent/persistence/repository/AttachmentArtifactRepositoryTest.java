package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.AttachmentArtifactEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-11 (V62): PostgreSQL contract for attachment artifact metadata —
 * unique dedupe, ownership isolation, expiry cleanup.
 */
@Tag("slow")
class AttachmentArtifactRepositoryTest extends PostgresTestContainer {

    @Autowired
    private AttachmentArtifactRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanTable() {
        transactionTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

    private AttachmentArtifactEntity entity(String owner, String hash, String disposition) {
        AttachmentArtifactEntity entity = new AttachmentArtifactEntity();
        entity.setId("att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        entity.setOwnerId(owner);
        entity.setContentHash(hash);
        entity.setDisposition(disposition);
        entity.setOrigin("telegram");
        return entity;
    }

    @Test
    void retiringSessionArtifactsRollsBackWithSessionDeletion() {
        UUID sessionId = UUID.randomUUID();
        var artifact = entity("alice", "session-file", "photo");
        artifact.setSessionId(sessionId);
        repository.saveAndFlush(artifact);
        var cutoff = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(repository.expireForSession(sessionId, cutoff)).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(repository.findById(artifact.getId()).orElseThrow().getExpiresAt()).isAfter(cutoff);

        transactionTemplate.executeWithoutResult(tx ->
            assertThat(repository.expireForSession(sessionId, cutoff)).isEqualTo(1));
        assertThat(repository.findById(artifact.getId()).orElseThrow().getExpiresAt()).isEqualTo(cutoff);
    }

    @Test
    void dedupeLookupFindsOwnerScopedArtifact() {
        repository.save(entity("alice", "hash-a", "photo"));
        repository.save(entity("bob", "hash-a", "photo"));

        Optional<AttachmentArtifactEntity> aliceHit = repository
            .findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope("alice", "hash-a", "photo", null, "");
        Optional<AttachmentArtifactEntity> carolMiss = repository
            .findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope("carol", "hash-a", "photo", null, "");

        assertThat(aliceHit).isPresent();
        assertThat(carolMiss).isEmpty();
    }

    @Test
    void identicalContentDeduplicatesOnlyWithinOneSession() {
        UUID firstSession = UUID.randomUUID();
        UUID secondSession = UUID.randomUUID();
        var first = entity("alice", "same", "photo");
        first.setSessionId(firstSession);
        var second = entity("alice", "same", "photo");
        second.setSessionId(secondSession);
        repository.saveAndFlush(first);
        repository.saveAndFlush(second);
        assertThat(repository.findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope("alice", "same", "photo", firstSession, ""))
            .get().extracting(AttachmentArtifactEntity::getId).isEqualTo(first.getId());
        assertThat(repository.findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope("alice", "same", "photo", secondSession, ""))
            .get().extracting(AttachmentArtifactEntity::getId).isEqualTo(second.getId());
        var duplicate = entity("alice", "same", "photo");
        duplicate.setSessionId(firstSession);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneConcurrentOutboundClaimWinsAndUnknownDoesNotRetry() throws Exception {
        var session = UUID.randomUUID();
        var artifact = entity("alice", "claim", "document");
        artifact.setSessionId(session);
        repository.saveAndFlush(artifact);
        var now = Instant.now();

        assertThat(repository.claimOutbound(artifact.getId(), "bob", session, now)).isZero();
        assertThat(repository.claimOutbound(artifact.getId(), "alice", UUID.randomUUID(), now)).isZero();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> attempt = () -> {
                start.await();
                return repository.claimOutbound(artifact.getId(), "alice", session, now);
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                second.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 1);
        }

        assertThat(repository.claimOutbound(artifact.getId(), "alice", session, now)).isZero();
        var stored = repository.findById(artifact.getId()).orElseThrow();
        assertThat(stored.getOutboundClaimedAt()).isNotNull();
        assertThat(stored.getDeliveredMessageId()).isNull();
    }

    @Test
    void onlyTheCurrentUndeliveredClaimCanBeReleased() {
        UUID session = UUID.randomUUID();
        var artifact = entity("alice", "release", "photo");
        artifact.setSessionId(session);
        repository.saveAndFlush(artifact);
        String id = artifact.getId();
        Instant first = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant second = first.plus(1, java.time.temporal.ChronoUnit.MICROS);

        assertThat(repository.claimOutbound(id, "alice", session, first)).isOne();
        assertThat(repository.releaseOutbound(id, "bob", session, first)).isZero();
        assertThat(repository.releaseOutbound(id, "alice", UUID.randomUUID(), first)).isZero();
        assertThat(repository.releaseOutbound(id, "alice", session, first)).isOne();
        assertThat(repository.claimOutbound(id, "alice", session, second)).isOne();
        assertThat(repository.releaseOutbound(id, "alice", session, first)).isZero();
        assertThat(repository.recordOutboundReceipt(id, "alice", session, "42", second)).isOne();
        assertThat(repository.releaseOutbound(id, "alice", session, second)).isZero();
    }

    @Test
    void concurrentReceiptsPreserveTheFirstAcceptedMessage() throws Exception {
        UUID session = UUID.randomUUID();
        var artifact = entity("alice", "receipt-race", "photo");
        artifact.setSessionId(session);
        repository.saveAndFlush(artifact);
        Instant now = Instant.now();
        assertThat(repository.recordOutboundReceipt(artifact.getId(), "alice", session, "41", now)).isZero();
        assertThat(repository.claimOutbound(artifact.getId(), "alice", session, now)).isOne();

        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return repository.recordOutboundReceipt(artifact.getId(), "alice", session, "42", now);
            });
            var second = executor.submit(() -> {
                start.await();
                return repository.recordOutboundReceipt(artifact.getId(), "alice", session, "43", now);
            });
            start.countDown();
            int firstResult = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            int secondResult = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(firstResult, secondResult)).containsExactlyInAnyOrder(0, 1);
            String winner = firstResult == 1 ? "42" : "43";
            assertThat(repository.recordOutboundReceipt(artifact.getId(), "alice", session, winner, now.plusSeconds(1)))
                .isOne();
            var stored = repository.findById(artifact.getId()).orElseThrow();
            assertThat(stored.getDeliveredMessageId()).isEqualTo(winner);
            assertThat(stored.getDeliveredAt()).isBefore(now.plusSeconds(1));
        }
    }

    @Test
    void sessionLinkageOrdersByCreation() {
        UUID sessionId = UUID.randomUUID();
        AttachmentArtifactEntity first = entity("alice", "h1", "document");
        first.setSessionId(sessionId);
        AttachmentArtifactEntity second = entity("alice", "h2", "photo");
        second.setSessionId(sessionId);
        repository.save(first);
        repository.save(second);

        List<AttachmentArtifactEntity> linked = repository
            .findBySessionIdOrderByCreatedAtAsc(sessionId);

        assertThat(linked).hasSize(2);
        assertThat(linked).extracting(AttachmentArtifactEntity::getContentHash)
            .containsExactly("h1", "h2");
    }

    @Test
    void expirySweepDeletesOnlyConfirmedFilesAndRetriesFailures(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path cache
    ) throws Exception {
        var fresh = entity("alice", "fresh", "photo");
        fresh.setExpiresAt(Instant.now().plusSeconds(3600));
        fresh.setCachePath("fresh.bin");
        var stale = entity("alice", "stale", "photo");
        stale.setExpiresAt(Instant.now().minusSeconds(60));
        stale.setCachePath("stale.bin");
        var blocked = entity("alice", "blocked", "photo");
        blocked.setExpiresAt(stale.getExpiresAt());
        blocked.setCachePath("blocked.bin");
        var missing = entity("alice", "missing", "photo");
        missing.setExpiresAt(stale.getExpiresAt());
        missing.setCachePath("missing.bin");
        java.nio.file.Files.writeString(cache.resolve("fresh.bin"), "fresh");
        java.nio.file.Files.writeString(cache.resolve("stale.bin"), "stale");
        java.nio.file.Files.createDirectory(cache.resolve("blocked.bin"));
        repository.saveAllAndFlush(List.of(fresh, stale, blocked, missing));
        var service = new com.azhukov.agent.service.AttachmentArtifactService(
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public AttachmentArtifactRepository getObject() { return repository; }
                @Override public AttachmentArtifactRepository getIfAvailable() { return repository; }
            }, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cacheRootConfig", cache.toString());

        assertThat(transactionTemplate.<Integer>execute(tx -> service.sweepExpired())).isEqualTo(2);
        assertThat(repository.findById(stale.getId())).isEmpty();
        assertThat(repository.findById(missing.getId())).isEmpty();
        assertThat(repository.findById(blocked.getId())).isPresent();
        assertThat(repository.findById(fresh.getId())).isPresent();
        assertThat(cache.resolve("fresh.bin")).exists();

        java.nio.file.Files.delete(cache.resolve("blocked.bin"));
        java.nio.file.Files.writeString(cache.resolve("blocked.bin"), "retry");
        assertThat(transactionTemplate.<Integer>execute(tx -> service.sweepExpired())).isEqualTo(1);
        assertThat(repository.findById(blocked.getId())).isEmpty();
        assertThat(cache.resolve("blocked.bin")).doesNotExist();
    }

    @Test
    void sweepDeleteDoesNotPickUpARecordThatExpiredAfterSelection() {
        Instant cutoff = Instant.now();
        var selected = entity("alice", "selected", "photo");
        selected.setExpiresAt(cutoff.minusSeconds(1));
        var later = entity("alice", "later", "photo");
        later.setExpiresAt(cutoff.plusSeconds(1));
        repository.saveAllAndFlush(List.of(selected, later));
        var ids = repository.findByExpiresAtBefore(cutoff).stream().map(AttachmentArtifactEntity::getId).toList();

        int removed = transactionTemplate.execute(tx -> repository.deleteExpiredByIds(ids, cutoff));
        assertThat(removed).isEqualTo(1);
        assertThat(repository.findById(later.getId())).isPresent();
        assertThat(repository.findByExpiresAtBefore(cutoff.plusSeconds(2))).extracting(AttachmentArtifactEntity::getId)
            .containsExactly(later.getId());
    }

    // ── V65: outbound delivery receipt columns ──

    @Test
    void deliveredReceiptColumnsPersist() {
        AttachmentArtifactEntity e = entity("alice", "hash-v64", "photo");
        e.setState("delivered");
        e.setDeliveredMessageId("42133");
        e.setDeliveredAt(Instant.now());
        AttachmentArtifactEntity saved = repository.save(e);

        AttachmentArtifactEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getDeliveredMessageId()).isEqualTo("42133");
        assertThat(loaded.getDeliveredAt()).isNotNull();
        assertThat(loaded.getState()).isEqualTo("delivered");
    }

    @Test
    void receiptColumnsNullByDefault() {
        AttachmentArtifactEntity saved = repository.save(entity("bob", "hash-v64b", "file"));
        AttachmentArtifactEntity loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getDeliveredMessageId()).isNull();
        assertThat(loaded.getDeliveredAt()).isNull();
    }

    @Test
    void receiptOnReceivedStateViolatesCheck() {
        // CHECK constraint: receipt columns require state='delivered'
        AttachmentArtifactEntity e = entity("carol", "hash-v64c", "file");
        e.setState("received");
        e.setDeliveredMessageId("999");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                transactionTemplate.execute(tx -> {
                    repository.saveAndFlush(e);
                    return null;
                }))
            .hasMessageContaining("chk_artifact_receipt_state");
        // the failing row must not be persisted
        Optional<AttachmentArtifactEntity> after = repository.findAll().stream()
            .filter(x -> "hash-v64c".equals(x.getContentHash())).findFirst();
        assertThat(after).isEmpty();
    }
}
