package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
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
 * WP-3 (V58) PostgreSQL slowTest: OAuth flow state lifecycle + token table
 * migration columns (profile default, key version).
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
class McpOAuthFlowRepositoryTest extends PostgresTestContainer {

    @Autowired
    private McpOAuthFlowRepository flowRepository;

    @Autowired
    private com.azhukov.agent.persistence.repository.McpOAuthRepository tokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void flowStateIsUniqueAndLookedUpByHash() {
        McpOAuthFlowEntity flow = transactionTemplate.execute(tx -> {
            flowRepository.deleteAll();
            McpOAuthFlowEntity entity = new McpOAuthFlowEntity();
            entity.setProfile("default");
            entity.setServerName("srv");
            entity.setStateHash("hash-" + UUID.randomUUID());
            entity.setCodeChallenge("challenge");
            entity.setVerifierEncrypted("enc");
            entity.setRedirectUri("https://local/cb");
            entity.setAuthorizationUrl("https://auth/authorize");
            entity.setExpiresAt(Instant.now().plusSeconds(600));
            return flowRepository.save(entity);
        });

        Optional<McpOAuthFlowEntity> found = flowRepository.findByStateHash(flow.getStateHash());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("pending");

        // duplicate state hash rejected
        McpOAuthFlowEntity duplicate = new McpOAuthFlowEntity();
        duplicate.setProfile("default");
        duplicate.setServerName("srv");
        duplicate.setStateHash(flow.getStateHash());
        duplicate.setCodeChallenge("challenge");
        duplicate.setVerifierEncrypted("enc");
        duplicate.setRedirectUri("https://local/cb");
        duplicate.setAuthorizationUrl("https://auth/authorize");
        duplicate.setExpiresAt(Instant.now().plusSeconds(600));
        UUID finalId = flow.getId();
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                flowRepository.saveAndFlush(duplicate)))
            .isInstanceOf(Exception.class);

        transactionTemplate.executeWithoutResult(tx -> flowRepository.deleteById(finalId));
    }

    @Test
    void tokenRowsCarryMigrationColumns() {
        McpOAuthEntity token = transactionTemplate.execute(tx -> {
            McpOAuthEntity entity = new McpOAuthEntity();
            entity.setServerName("wp3-tok-" + UUID.randomUUID());
            entity.setAccessToken("enc-token");
            entity.setProfile("default");
            entity.setEncryptionKeyVersion(1);
            entity.setTokenScope("read write");
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return tokenRepository.save(entity);
        });

        assertThat(token.getProfile()).isEqualTo("default");
        assertThat(token.getEncryptionKeyVersion()).isEqualTo(1);
        assertThat(token.getTokenScope()).isEqualTo("read write");

        transactionTemplate.executeWithoutResult(tx -> tokenRepository.deleteById(token.getId()));
    }
}
