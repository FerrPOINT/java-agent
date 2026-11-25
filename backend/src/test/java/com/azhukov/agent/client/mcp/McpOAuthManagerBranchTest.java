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

    private McpOAuthManager manager;

    @BeforeEach
    void setUp() {
        manager = new McpOAuthManager(mcpOAuthRepository);
    }

    // ── getToken ──

    @Test
    void getToken_emptyEntity_returnsEmpty() {
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.empty());
        assertThat(manager.getToken("default", server("srv"))).isEmpty();
    }

    @Test
    void getToken_nonExpiredToken_returnsToken() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setTokenIssuer("https://mcp.example.com");
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(entity));
        Optional<String> token = manager.getToken("default", server("srv"));
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getToken_nullExpiresAt_returnsToken() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setTokenIssuer("https://mcp.example.com");
        entity.setAccessToken("access-no-expiry");
        entity.setExpiresAt(null);
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(entity));
        Optional<String> token = manager.getToken("default", server("srv"));
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-no-expiry");
    }

    @Test
    void getToken_expiredToken_refreshFails_returnsEmpty() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setTokenIssuer("https://mcp.example.com");
        entity.setAccessToken("expired");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(entity));
        // No refresh token is stored, so automatic refresh fails.
        assertThat(manager.getToken("default", server("srv"))).isEmpty();
    }

    @Test
    void getToken_concurrentlyRefreshedToken_returnsCurrentBoundToken() {
        McpOAuthEntity expired = new McpOAuthEntity();
        expired.setTokenIssuer("https://mcp.example.com");
        expired.setAccessToken("old-token");
        expired.setRefreshToken("refresh-token");
        expired.setExpiresAt(Instant.now().minusSeconds(3600));

        McpOAuthEntity refreshed = new McpOAuthEntity();
        refreshed.setTokenIssuer("https://mcp.example.com");
        refreshed.setAccessToken("new-token");
        refreshed.setRefreshToken("refresh-token");
        refreshed.setExpiresAt(Instant.now().plusSeconds(3600));

        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv"))
            .thenReturn(Optional.of(expired))
            .thenReturn(Optional.of(refreshed));

        // A concurrent refresh may publish a valid bound token while this attempt fails.
        Optional<String> token = manager.getToken("default", server("srv"));
        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("new-token");
    }

    // ── storeToken ──

    @Test
    void storeToken_newEntity_createsAndSaves() {
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.empty());
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        manager.storeToken("default", "srv", "access", "refresh", expiresAt, "https://mcp.example.com/oauth/token");

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    @Test
    void storeToken_existingEntity_updatesAndSaves() {
        McpOAuthEntity existing = new McpOAuthEntity();
        existing.setCreatedAt(Instant.now().minusSeconds(86400));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(existing));
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        manager.storeToken("default", "srv", "new-access", "new-refresh", expiresAt, "https://mcp.example.com/oauth/token");

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    @Test
    void storeToken_nullCreatedAt_setsCreatedAt() {
        McpOAuthEntity existing = new McpOAuthEntity();
        existing.setCreatedAt(null);
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(existing));
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.storeToken("default", "srv", "access", "refresh", Instant.now().plusSeconds(3600), "https://mcp.example.com/oauth/token");

        verify(mcpOAuthRepository).save(any(McpOAuthEntity.class));
    }

    // ── refreshToken ──

    @Test
    void refreshToken_noTokenUrl_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");

        assertThatThrownBy(() -> manager.refreshToken("default", server))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No OAuth token URL configured");
    }

    @Test
    void refreshToken_noStoredToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");

        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.refreshToken("default", server))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No stored OAuth token");
    }

    @Test
    void refreshToken_noRefreshToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken(null);
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> manager.refreshToken("default", server))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
    }

    @Test
    void refreshToken_blankRefreshToken_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("srv");
        server.setOauthTokenUrl("https://example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken("  ");
        when(mcpOAuthRepository.findByProfileAndServerName("default", "srv")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> manager.refreshToken("default", server))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
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

    private static AgentProperties.McpProperties.ServerProperties server(String name) {
        var server = new AgentProperties.McpProperties.ServerProperties();
        server.setName(name);
        server.setOauthTokenUrl("https://mcp.example.com/oauth/token");
        return server;
    }
}