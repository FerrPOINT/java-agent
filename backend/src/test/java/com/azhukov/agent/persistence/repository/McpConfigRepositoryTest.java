package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-3 (V57) PostgreSQL slowTest: profile-scoped config uniqueness, revision
 * monotonicity per profile isolation, schema cache cascade + latest-read.
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
    "agent.skills.enabled=false",
})
class McpConfigRepositoryTest extends PostgresTestContainer {

    @Autowired
    private McpServerConfigRepository configRepository;

    @Autowired
    private McpSchemaCacheRepository schemaCacheRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
