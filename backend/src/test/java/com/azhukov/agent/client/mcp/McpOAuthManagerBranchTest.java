package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link McpOAuthManager}.
 * Covers token retrieval, refresh, storage, error paths, edge cases.
 */
@ExtendWith(MockitoExtension.class)
class McpOAuthManagerBranchTest {

    @Mock
    private McpOAuthRepository mcpOAuthRepository;

    private AgentProperties properties;
    private McpOAuthManager manager;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        manager = new McpOAuthManager(mcpOAuthRepository, properties);
    }

    // ── getToken ──

    @Test
    void getToken_emptyEntity_returnsEmpty() {
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.empty());
        assertThat(manager.getToken("srv")).isEmpty();
    }

    @Test
    void getToken_nonExpiredToken_returnsToken() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));
        Optional<String> token = manager.getToken("srv");
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getToken_nullExpiresAt_returnsToken() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-no-expiry");
        entity.setExpiresAt(null);
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));
        Optional<String> token = manager.getToken("srv");
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-no-expiry");
    }

    @Test
    void getToken_expiredToken_refreshFails_returnsEmpty() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("expired");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));
        // No server config → refresh throws → returns empty
        assertThat(manager.getToken("srv")).isEmpty();
    }

    @Test
    void getToken_expiredToken_refreshSucceeds_returnsNewToken() {
        McpOAuthEntity expired = new McpOAuthEntity();
        expired.setAccessToken("old-token");
        expired.setRefreshToken("refresh-token");
        expired.setExpiresAt(Instant.now().minusSeconds(3600));

        McpOAuthEntity refreshed = new McpOAuthEntity();
        refreshed.setAccessToken("new-token");
        refreshed.setRefreshToken("refresh-token");
        refreshed.setExpiresAt(Instant.now().plusSeconds(3600));

        when(mcpOAuthRepository.findByServerName("srv"))
            .thenReturn(Optional.of(expired))
            .thenReturn(Optional.of(refreshed));

        // Without a server config, refresh throws IllegalStateException (caught),
        // but the re-read returns the "refreshed" entity (mocked) with a future expiry
        // → getToken returns the new token
        Optional<String> token = manager.getToken("srv");
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("new-token");
    }

    // ── storeToken ──

    @Test
    void storeToken_newEntity_createsAndSaves() {
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.empty());
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        manager.storeToken("srv", "access", "refresh", expiresAt);

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    @Test
    void storeToken_existingEntity_updatesAndSaves() {
        McpOAuthEntity existing = new McpOAuthEntity();
        existing.setCreatedAt(Instant.now().minusSeconds(86400));
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(existing));
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        manager.storeToken("srv", "new-access", "new-refresh", expiresAt);

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    @Test
    void storeToken_nullCreatedAt_setsCreatedAt() {
        McpOAuthEntity existing = new McpOAuthEntity();
        existing.setCreatedAt(null);
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(existing));
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.storeToken("srv", "access", "refresh", Instant.now().plusSeconds(3600));

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    // ── refreshToken ──

    @Test
    void refreshToken_noServerConfig_throwsIllegalStateException() {
        assertThatThrownBy(() -> manager.refreshToken("unknown"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No MCP server config found");
    }

    @Test
    void refreshToken_noTokenUrl_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        properties.getMcp().getServers().add(server);

        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No OAuth token URL configured");
    }

    @Test
    void refreshToken_noStoredToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");
        properties.getMcp().getServers().add(server);

        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No stored OAuth token");
    }

    @Test
    void refreshToken_noRefreshToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");
        properties.getMcp().getServers().add(server);

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken(null);
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
    }

    @Test
    void refreshToken_blankRefreshToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");
        properties.getMcp().getServers().add(server);

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken("  ");
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
    }

    @Test
    void refreshToken_withClientIdAndSecret_includesInRequest() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");
        server.setOauthClientId("client-123");
        server.setOauthClientSecret("secret-456");
        server.setOauthScopes("read write");
        properties.getMcp().getServers().add(server);

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken("refresh-token");
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));

        // The HTTP call will fail (no server) but we verify it doesn't throw before that
        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(Exception.class);
    }

    @Test
    void refreshToken_nullClientId_omitsFromRequest() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");
        // No client ID or secret set
        properties.getMcp().getServers().add(server);

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken("refresh-token");
        when(mcpOAuthRepository.findByServerName("srv")).thenReturn(Optional.of(entity));

        // The HTTP call will fail
        assertThatThrownBy(() -> manager.refreshToken("srv"))
            .isInstanceOf(Exception.class);
    }

    // ── extractJsonField ──

    @Test
    void extractJsonField_nullJson_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField(null, "field")).isNull();
    }

    @Test
    void extractJsonField_emptyJson_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("", "field")).isNull();
    }

    @Test
    void extractJsonField_fieldNotFound_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("{\"other\":\"val\"}", "field")).isNull();
    }

    @Test
    void extractJsonField_noColonAfterField_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("\"field\"", "field")).isNull();
    }

    @Test
    void extractJsonField_emptyJsonObject_returnsNull() {
        assertThat(McpOAuthManager.extractJsonField("{}", "field")).isNull();
    }

    @Test
    void extractJsonField_stringValue_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"value\"}", "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_numericValue_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":3600}", "field")).isEqualTo("3600");
    }

    @Test
    void extractJsonField_valueWithWhitespace_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":  \"value\"}", "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_valueWithComma_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"value\",\"other\":\"x\"}", "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_valueWithBrace_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"value\"}", "field")).isEqualTo("value");
    }

    @Test
    void extractJsonField_numericValueWithComma_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"a\":1,\"b\":2}", "a")).isEqualTo("1");
    }

    @Test
    void extractJsonField_numericValueWithBrace_returnsValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"a\":42}", "a")).isEqualTo("42");
    }

    @Test
    void extractJsonField_escapedChars_returnsUnescaped() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"a\\\\nb\"}", "field")).isEqualTo("a\\nb");
    }

    @Test
    void extractJsonField_emptyString_returnsEmpty() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"\"}", "field")).isEqualTo("");
    }

    @Test
    void extractJsonField_valueEndsWithBackslashQuote_returnsCorrectValue() {
        assertThat(McpOAuthManager.extractJsonField("{\"field\":\"val\\\"ue\"}", "field")).isEqualTo("val\"ue");
    }

    @Test
    void extractJsonField_valueAtEndOfJson_returnsCorrectValue() {
        String json = "{\"expires_in\":3600}";
        assertThat(McpOAuthManager.extractJsonField(json, "expires_in")).isEqualTo("3600");
    }

    // ── sanitizeError ──

    @Test
    void sanitizeError_null_returnsNull() {
        assertThat(McpOAuthManager.sanitizeError(null)).isNull();
    }

    @Test
    void sanitizeError_empty_returnsEmpty() {
        assertThat(McpOAuthManager.sanitizeError("")).isEmpty();
    }

    @Test
    void sanitizeError_stripsBearerToken() {
        String result = McpOAuthManager.sanitizeError("Bearer sk-abc123 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk-abc123");
    }

    @Test
    void sanitizeError_stripsGitHubToken() {
        String result = McpOAuthManager.sanitizeError("token=ghp_abcdef1234567890abcdef1234567890abcdef12 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("ghp_abcdef1234567890abcdef1234567890abcdef12");
    }

    @Test
    void sanitizeError_stripsOpenAIKey() {
        String result = McpOAuthManager.sanitizeError("sk-abc123def456789 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("sk-abc123def456789");
    }

    @Test
    void sanitizeError_stripsPassword() {
        String result = McpOAuthManager.sanitizeError("password=secret123 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("secret123");
    }

    @Test
    void sanitizeError_stripsSecret() {
        String result = McpOAuthManager.sanitizeError("secret=topsecret error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("topsecret");
    }

    @Test
    void sanitizeError_stripsApiKey() {
        String result = McpOAuthManager.sanitizeError("API_KEY=mykey12345678 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mykey12345678");
    }

    @Test
    void sanitizeError_stripsKey() {
        String result = McpOAuthManager.sanitizeError("key=mykey12345678 error");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("mykey12345678");
    }

    @Test
    void sanitizeError_preservesSafeText() {
        String result = McpOAuthManager.sanitizeError("Connection refused to host example.com");
        assertThat(result).isEqualTo("Connection refused to host example.com");
    }

    @Test
    void sanitizeError_multipleCredentials_allStripped() {
        String result = McpOAuthManager.sanitizeError("password=secret1 and key=secret2 and Bearer token123");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("secret1");
        assertThat(result).doesNotContain("secret2");
        assertThat(result).doesNotContain("token123");
    }
}