package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:mcpoauthrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
@Transactional
class McpOAuthRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T12:00:00Z");
    private static final String SERVER_NAME_1 = "github";
    private static final String SERVER_NAME_2 = "google";
    private static final String ACCESS_TOKEN_1 = "access-token-123";
    private static final String ACCESS_TOKEN_2 = "access-token-456";
    private static final String REFRESH_TOKEN_1 = "refresh-token-123";

    @Autowired
    private McpOAuthRepository mcpOAuthRepository;

    @Test
    void saveAndFindById() {
        McpOAuthEntity entity = newMcpOAuth(SERVER_NAME_1, ACCESS_TOKEN_1, REFRESH_TOKEN_1);

        McpOAuthEntity saved = mcpOAuthRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        McpOAuthEntity found = mcpOAuthRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getServerName()).isEqualTo(SERVER_NAME_1);
        assertThat(found.getAccessToken()).isEqualTo(ACCESS_TOKEN_1);
        assertThat(found.getRefreshToken()).isEqualTo(REFRESH_TOKEN_1);
    }

    @Test
    void findByServerNameReturnsEntity() {
        mcpOAuthRepository.save(newMcpOAuth(SERVER_NAME_1, ACCESS_TOKEN_1, REFRESH_TOKEN_1));

        McpOAuthEntity found = mcpOAuthRepository.findByServerName(SERVER_NAME_1).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getServerName()).isEqualTo(SERVER_NAME_1);
    }

    @Test
    void findAllReturnsSavedEntities() {
        mcpOAuthRepository.save(newMcpOAuth(SERVER_NAME_1, ACCESS_TOKEN_1, REFRESH_TOKEN_1));
        mcpOAuthRepository.save(newMcpOAuth(SERVER_NAME_2, ACCESS_TOKEN_2, null));

        List<McpOAuthEntity> all = mcpOAuthRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(McpOAuthEntity::getServerName)
            .containsExactlyInAnyOrder(SERVER_NAME_1, SERVER_NAME_2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        McpOAuthEntity saved = mcpOAuthRepository.save(newMcpOAuth(SERVER_NAME_1, ACCESS_TOKEN_1, REFRESH_TOKEN_1));
        UUID id = saved.getId();

        assertThat(mcpOAuthRepository.findById(id)).isPresent();

        mcpOAuthRepository.deleteById(id);

        assertThat(mcpOAuthRepository.findById(id)).isEmpty();
        assertThat(mcpOAuthRepository.findByServerName(SERVER_NAME_1)).isEmpty();
    }

    @Test
    void saveUpdatesExistingEntity() {
        McpOAuthEntity saved = mcpOAuthRepository.save(newMcpOAuth(SERVER_NAME_1, ACCESS_TOKEN_1, REFRESH_TOKEN_1));
        UUID id = saved.getId();

        saved.setAccessToken("updated-token");
        saved.setUpdatedAt(T2);
        mcpOAuthRepository.save(saved);

        McpOAuthEntity found = mcpOAuthRepository.findById(id).orElseThrow();
        assertThat(found.getAccessToken()).isEqualTo("updated-token");
    }

    private McpOAuthEntity newMcpOAuth(String serverName, String accessToken, String refreshToken) {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setServerName(serverName);
        entity.setAccessToken(accessToken);
        entity.setRefreshToken(refreshToken);
        entity.setExpiresAt(T2);
        entity.setCreatedAt(T1);
        entity.setUpdatedAt(T1);
        return entity;
    }
}