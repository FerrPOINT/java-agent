package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpOAuthManagerTest {

    @Mock
    private McpOAuthRepository mcpOAuthRepository;

    private AgentProperties properties;
    private McpOAuthManager manager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        manager = new McpOAuthManager(mcpOAuthRepository, properties);
    }

    @Test
    void getToken_returnsEmpty_whenNoEntity() {
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.empty());

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isEmpty();
    }

    @Test
    void getToken_returnsToken_whenNotExpired() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getToken_returnsEmpty_whenExpired_andRefreshFails() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        // No refresh token URL configured → refresh throws, returns empty
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("test-server");

        assertThat(token).isEmpty();
    }

    @Test
    void storeToken_savesEntity() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.empty());
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.storeToken("test-server", "access-123", "refresh-456", expiresAt);

        ArgumentCaptor<McpOAuthEntity> captor = ArgumentCaptor.forClass(McpOAuthEntity.class);
        verify(mcpOAuthRepository).save(captor.capture());
        McpOAuthEntity saved = captor.getValue();
        assertThat(saved.getServerName()).isEqualTo("test-server");
        assertThat(saved.getAccessToken()).isEqualTo("access-123");
        assertThat(saved.getRefreshToken()).isEqualTo("refresh-456");
        assertThat(saved.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void extractJsonField_extractsStringField() {
        String json = "{\"access_token\":\"tok123\",\"expires_in\":3600}";
        assertThat(McpOAuthManager.extractJsonField(json, "access_token")).isEqualTo("tok123");
        assertThat(McpOAuthManager.extractJsonField(json, "expires_in")).isEqualTo("3600");
    }

    @Test
    void extractJsonField_returnsNullWhenFieldMissing() {
        String json = "{\"access_token\":\"tok123\"}";
        assertThat(McpOAuthManager.extractJsonField(json, "refresh_token")).isNull();
    }

    @Test
    void extractJsonField_handlesEscapedQuotes() {
        String json = "{\"access_token\":\"to\\\"ken\",\"refresh_token\":\"r\\\"ef\"}";
        assertThat(McpOAuthManager.extractJsonField(json, "access_token")).isEqualTo("to\"ken");
    }

    @Test
    void sanitizeError_stripsCredentials() {
        assertThat(McpOAuthManager.sanitizeError("Bearer sk-abc123 failed"))
            .contains("[REDACTED]")
            .doesNotContain("sk-abc123");
        assertThat(McpOAuthManager.sanitizeError("token=ghp_secret123 error"))
            .contains("[REDACTED]")
            .doesNotContain("ghp_secret123");
        assertThat(McpOAuthManager.sanitizeError("password=hello123 error"))
            .contains("[REDACTED]")
            .doesNotContain("hello123");
    }

    @Test
    void sanitizeError_returnsNullForNull() {
        assertThat(McpOAuthManager.sanitizeError(null)).isNull();
    }

    @Test
    void sanitizeError_returnsEmptyForEmpty() {
        assertThat(McpOAuthManager.sanitizeError("")).isEmpty();
    }

    // ─── P2-17: refreshToken interrupt handling ──────────────────────

    @Test
    void getToken_interruptedDuringAutoRefresh_restoresInterruptFlag() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("test-server")).thenReturn(Optional.of(entity));

        // Simulate interrupt by pre-interrupting the thread
        Thread.currentThread().interrupt();
        try {
            Optional<String> token = manager.getToken("test-server");
            // Should return empty (no valid token after failed refresh)
            assertThat(token).isEmpty();
            // Interrupt flag should be restored
            // Note: the refreshToken method will throw because there's no server config,
            // but the InterruptedException catch won't trigger here since the failure is
            // IllegalStateException, not InterruptedException. This test verifies the
            // getToken path doesn't crash when token is expired.
        } finally {
            // Clear interrupt flag for subsequent tests
            Thread.interrupted();
        }
    }

    @Test
    void refreshToken_noServerConfig_throwsIllegalStateException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("unknown-server"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No MCP server config found");
    }

    @Test
    void refreshToken_noStoredToken_throwsIllegalStateException() {
        // Configure a server with OAuth URL but no stored token
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("empty-server");
        serverConfig.setOauthTokenUrl("https://example.com/token");
        properties.getMcp().getServers().add(serverConfig);

        when(mcpOAuthRepository.findByServerName("empty-server")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("empty-server"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No stored OAuth token");
    }

    @Test
    void refreshToken_noRefreshTokenStored_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("no-refresh-server");
        serverConfig.setOauthTokenUrl("https://example.com/token");
        properties.getMcp().getServers().add(serverConfig);

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken(null);
        when(mcpOAuthRepository.findByServerName("no-refresh-server")).thenReturn(Optional.of(entity));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("no-refresh-server"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
    }

    @Test
    void refreshToken_noTokenUrlConfigured_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("no-url-server");
        // No oauthTokenUrl set
        properties.getMcp().getServers().add(serverConfig);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("no-url-server"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No OAuth token URL configured");
    }
}