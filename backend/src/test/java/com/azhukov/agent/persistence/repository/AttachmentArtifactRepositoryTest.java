package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.AttachmentArtifactEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
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
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false"
})
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
    void dedupeLookupFindsOwnerScopedArtifact() {
        repository.save(entity("alice", "hash-a", "photo"));
        repository.save(entity("bob", "hash-a", "photo"));

        Optional<AttachmentArtifactEntity> aliceHit = repository
            .findFirstByOwnerIdAndContentHashAndDisposition("alice", "hash-a", "photo");
        Optional<AttachmentArtifactEntity> carolMiss = repository
            .findFirstByOwnerIdAndContentHashAndDisposition("carol", "hash-a", "photo");

        assertThat(aliceHit).isPresent();
        assertThat(carolMiss).isEmpty();
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
    void expirySweepDeletesOnlyExpired() {
        AttachmentArtifactEntity fresh = entity("alice", "fresh", "photo");
        fresh.setExpiresAt(Instant.now().plusSeconds(3600));
        AttachmentArtifactEntity stale = entity("alice", "stale", "photo");
        stale.setExpiresAt(Instant.now().minusSeconds(60));
        repository.save(fresh);
        repository.save(stale);

        int removed = transactionTemplate.execute(tx ->
            repository.deleteExpired(Instant.now()));

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findById(fresh.getId())).isPresent();
        assertThat(repository.findById(stale.getId())).isEmpty();
    }

    // ── V64: outbound delivery receipt columns ──

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
