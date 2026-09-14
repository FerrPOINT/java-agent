package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
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

/**
 * V63 (upstream 399238f2c2 parity): MCP OAuth tokens are isolated per
 * profile — unique (profile, server_name), scoped lookups only.
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
class McpOAuthProfileIsolationTest extends PostgresTestContainer {

    @Autowired
    private McpOAuthRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanTable() {
        transactionTemplate.executeWithoutResult(tx -> repository.deleteAll());
    }

    private McpOAuthEntity token(String profile, String server) {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setServerName(server);
        entity.setProfile(profile);
        entity.setAccessToken("tok-" + profile + "-" + server);
        entity.setRefreshToken("ref-" + profile);
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    @Test
    void sameServerNameAcrossProfilesIsPermittedAndIsolated() {
        McpOAuthEntity saved1 = transactionTemplate.execute(tx ->
            repository.save(token("profile-a", "linear")));
        McpOAuthEntity saved2 = transactionTemplate.execute(tx ->
            repository.save(token("profile-b", "linear")));

        assertThat(saved1).isNotNull();
        assertThat(saved2).isNotNull(); // V63: composite unique — both rows persist

        Optional<McpOAuthEntity> a = transactionTemplate.execute(tx ->
            repository.findByProfileAndServerName("profile-a", "linear"));
        Optional<McpOAuthEntity> b = transactionTemplate.execute(tx ->
            repository.findByProfileAndServerName("profile-b", "linear"));
        Optional<McpOAuthEntity> c = transactionTemplate.execute(tx ->
            repository.findByProfileAndServerName("profile-c", "linear"));

        assertThat(a).isPresent();
        assertThat(a.get().getAccessToken()).isEqualTo("tok-profile-a-linear");
        assertThat(b).isPresent();
        assertThat(b.get().getAccessToken()).isEqualTo("tok-profile-b-linear");
        assertThat(c).isEmpty(); // no cross-profile leak
    }

    @Test
    void duplicateProfileServerPairIsRejected() {
        transactionTemplate.executeWithoutResult(tx ->
            repository.save(token("profile-a", "notion")));

        // Expected constraint violation must be observed OUTSIDE the tx —
        // an exception caught inside marks the tx rollback-only.
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                repository.save(token("profile-a", "notion"));
                repository.flush();
            });
            assertThat(false).as("duplicate (profile, server_name) must be rejected").isTrue();
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // V63 composite unique constraint — expected
        }
        // and the original row survived untouched
        var survivor = transactionTemplate.execute(tx ->
            repository.findByProfileAndServerName("profile-a", "notion"));
        assertThat(survivor).isPresent();
        assertThat(survivor.get().getAccessToken()).isEqualTo("tok-profile-a-notion");
    }

    @Test
    void legacyDefaultProfileRowsScopedToDefault() {
        transactionTemplate.executeWithoutResult(tx ->
            repository.save(token("default", "github")));

        assertThat(transactionTemplate.execute(tx ->
                repository.findByProfileAndServerName("default", "github")).isPresent()).isTrue();
        assertThat(transactionTemplate.execute(tx ->
                repository.findByProfileAndServerName("other", "github")).isEmpty()).isTrue();
    }
}
