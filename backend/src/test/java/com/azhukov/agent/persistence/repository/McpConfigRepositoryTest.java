package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.service.McpConfigStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-3 (V57) PostgreSQL slowTest: profile-scoped config uniqueness, revision
 * monotonicity per profile isolation, schema cache cascade + latest-read.
 */
@Tag("slow")
class McpConfigRepositoryTest extends PostgresTestContainer {

    @Autowired
    private McpServerConfigRepository configRepository;

    @Autowired
    private McpSchemaCacheRepository schemaCacheRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private McpConfigStore configStore;

    private McpServerConfigEntity config(String profile, String name) {
        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setProfile(profile);
        entity.setName(name);
        entity.setTransport("stdio");
        entity.setCommand("npx");
        return entity;
    }

    @Test
    void sameServerNameIsolatedByProfile() {
        transactionTemplate.executeWithoutResult(tx -> {
            configRepository.deleteAll();
            configRepository.save(config("wp3-a", "shared"));
            configRepository.save(config("wp3-b", "shared"));
        });

        Optional<McpServerConfigEntity> a = configRepository.findByProfileAndName("wp3-a", "shared");
        Optional<McpServerConfigEntity> b = configRepository.findByProfileAndName("wp3-b", "shared");
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(a.get().getId()).isNotEqualTo(b.get().getId());
        transactionTemplate.executeWithoutResult(tx -> configRepository.deleteAll());
    }

    @Test
    void duplicateProfileNameRejectedByUniqueConstraint() {
        transactionTemplate.executeWithoutResult(tx -> {
            configRepository.deleteAll();
            configRepository.save(config("wp3-unique", "srv"));
        });
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                configRepository.saveAndFlush(config("wp3-unique", "srv"))))
            .isInstanceOf(Exception.class);
        transactionTemplate.executeWithoutResult(tx -> configRepository.deleteAll());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1, 2})
    void delayedOldDiscoveryCannotReplaceTheCommittedSchema(long oldRevision) throws Exception {
        UUID configId = transactionTemplate.execute(tx ->
            configRepository.save(config("wp3-cache-race", "srv")).getId());
        var oldObservation = Instant.parse("2026-11-10T00:00:00Z");
        var currentObservation = oldObservation.plusSeconds(1);
        var uncommitted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var newer = executor.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                var config = configRepository.lockById(configId).orElseThrow();
                config.setConfigRevision(2);
                configStore.storeSchema(configId, 2, currentObservation, "[\"current\"]", null, null);
                uncommitted.countDown();
                try {
                    assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(uncommitted.await(5, TimeUnit.SECONDS)).isTrue();
                var old = executor.submit(() -> configStore.storeSchema(configId, oldRevision, oldObservation, "[\"old\"]", null, null));
                assertThatThrownBy(() -> old.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
                commit.countDown();
                newer.get(5, TimeUnit.SECONDS);
                old.get(5, TimeUnit.SECONDS);
                configStore.recordSchemaError(configId, oldRevision, oldObservation, "late failure");
                var current = configStore.cachedSchema(configId, 2).orElseThrow();
                assertThat(current.getToolsJson()).isEqualTo("[\"current\"]");
                assertThat(current.getLastError()).isNull();
                var errorObservation = currentObservation.plusSeconds(1);
                configStore.recordSchemaError(configId, 2, errorObservation, "current failure");
                configStore.storeSchema(configId, 2, currentObservation, "[\"delayed\"]", null, null);
                var afterFailure = configStore.cachedSchema(configId, 2).orElseThrow();
                assertThat(afterFailure.getLastError()).isEqualTo("current failure");
                assertThat(afterFailure.getFetchedAt()).isEqualTo(errorObservation);
                assertThat(afterFailure.getLastSuccessAt()).isEqualTo(currentObservation);
                assertThat(afterFailure.getExpiresAt()).isEqualTo(current.getExpiresAt());
                assertThat(afterFailure.getToolsJson()).isEqualTo("[\"current\"]");
                var recoveredAt = errorObservation.plusSeconds(1);
                configStore.storeSchema(configId, 2, recoveredAt, "[\"recovered\"]", null, null);
                var recovered = configStore.cachedSchema(configId, 2).orElseThrow();
                assertThat(recovered.getToolsJson()).isEqualTo("[\"recovered\"]");
                assertThat(recovered.getLastError()).isNull();
                assertThat(recovered.getExpiresAt()).isAfter(current.getExpiresAt());
            } finally {
                commit.countDown();
            }
        } finally {
            transactionTemplate.executeWithoutResult(tx -> configRepository.deleteById(configId));
        }
    }

    @Test
    void schemaCacheCascadesOnConfigDeleteAndReadsLatest() {
        UUID configId = transactionTemplate.execute(tx -> {
            McpServerConfigEntity saved = configRepository.save(config("wp3-cache", "srv"));
            McpSchemaCacheEntity cache = new McpSchemaCacheEntity();
            cache.setServerConfigId(saved.getId());
            cache.setConfigRevision(1);
            cache.setContentHash("hash-1");
            cache.setToolsJson("[{\"name\":\"t\"}]");
            cache.setFetchedAt(Instant.now().minusSeconds(60));
            schemaCacheRepository.save(cache);

            McpSchemaCacheEntity newer = new McpSchemaCacheEntity();
            newer.setServerConfigId(saved.getId());
            newer.setConfigRevision(2);
            newer.setContentHash("hash-2");
            newer.setToolsJson("[{\"name\":\"t\"},{\"name\":\"u\"}]");
            newer.setFetchedAt(Instant.now());
            schemaCacheRepository.save(newer);
            return saved.getId();
        });

        Optional<McpSchemaCacheEntity> latest =
            schemaCacheRepository.findFirstByServerConfigIdOrderByFetchedAtDesc(configId);
        assertThat(latest).isPresent();
        assertThat(latest.get().getConfigRevision()).isEqualTo(2);

        // unique (server_config_id, config_revision) prevents duplicate rows per revision
        McpSchemaCacheEntity duplicate = new McpSchemaCacheEntity();
        duplicate.setServerConfigId(configId);
        duplicate.setConfigRevision(2);
        duplicate.setContentHash("hash-2");
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                schemaCacheRepository.saveAndFlush(duplicate)))
            .isInstanceOf(Exception.class);

        transactionTemplate.executeWithoutResult(tx ->
            configRepository.deleteById(configId));
        assertThat(schemaCacheRepository.findFirstByServerConfigIdOrderByFetchedAtDesc(configId))
            .isEmpty(); // cascade removed cache rows
    }
}
