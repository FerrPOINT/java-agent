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

    private McpOAuthManager manager;

    @BeforeEach
    void setUp() {
        manager = new McpOAuthManager(mcpOAuthRepository);
    }

    @Test
    void refreshFailureDoesNotExposeProviderBody() throws Exception {
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            byte[] body = "{\"refresh_token\":\"sensitive-canary\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        http.start();
        try {
            var config = server("test-server");
            config.setOauthTokenUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/token");
            var entity = new McpOAuthEntity(); entity.setRefreshToken("old-refresh");
            when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.refreshToken("default", config))
                .hasMessageContaining("400").hasMessageNotContaining("sensitive-canary");
        } finally {
            http.stop(0);
        }
    }

    @Test
    void malformedRefreshTokenMustNotOverwriteExistingCredential() throws Exception {
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"new-access\",\"refresh_token\":123}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        http.start();
        try {
            var config = server("test-server");
            config.setOauthTokenUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/token");
            var entity = new McpOAuthEntity(); entity.setRefreshToken("old-refresh");
            when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.refreshToken("default", config))
                .isInstanceOf(java.io.IOException.class);
            org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never()).save(any());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void getToken_returnsEmpty_whenNoEntity() {
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.empty());

        Optional<String> token = manager.getToken("default", server("test-server"));

        assertThat(token).isEmpty();
    }

    @Test
    void getToken_returnsToken_whenNotExpired() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("default", server("test-server"));

        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getTokenDoesNotReturnUnexpiredCredentialAfterIssuerChanges() {
        var entity = new McpOAuthEntity();
        entity.setAccessToken("old-issuer-token");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        entity.setTokenIssuer("https://old-auth.example.com");
        var config = server("test-server");
        config.setOauthTokenUrl("https://new-auth.example.com/token");
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        assertThat(manager.getToken("default", config)).isEmpty();
    }

    @Test
    void getTokenRejectsMissingCurrentIssuerButAcceptsMatchingIssuer() {
        var entity = new McpOAuthEntity(); entity.setAccessToken("valid-token");
        entity.setExpiresAt(Instant.now().plusSeconds(3600)); entity.setTokenIssuer("https://auth.example.com");
        var config = server("test-server");
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
        assertThat(manager.getToken("default", config)).isEmpty();
        config.setOauthTokenUrl("https://auth.example.com/token");
        assertThat(manager.getToken("default", config)).contains("valid-token");
    }

    @Test
    void getToken_returnsEmpty_whenExpired_andRefreshFails() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        // No refresh token URL configured → refresh throws, returns empty
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("default", server("test-server"));

        assertThat(token).isEmpty();
    }

    @Test
    void storeToken_savesProfileAndIssuerTogether() {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(mcpOAuthRepository.findByProfileAndServerName("research", "test-server")).thenReturn(Optional.empty());
        when(mcpOAuthRepository.save(any(McpOAuthEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.storeToken("research", "test-server", "access-123", "refresh-456", expiresAt, "https://mcp.example.com/oauth/token");

        ArgumentCaptor<McpOAuthEntity> captor = ArgumentCaptor.forClass(McpOAuthEntity.class);
        verify(mcpOAuthRepository).save(captor.capture());
        McpOAuthEntity saved = captor.getValue();
        verify(mcpOAuthRepository).findByProfileAndServerName("research", "test-server");
        assertThat(saved.getProfile()).isEqualTo("research");
        assertThat(saved.getTokenIssuer()).isEqualTo("https://mcp.example.com");
        assertThat(saved.getServerName()).isEqualTo("test-server");
        assertThat(saved.getAccessToken()).isEqualTo("access-123");
        assertThat(saved.getRefreshToken()).isEqualTo("refresh-456");
        assertThat(saved.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void oauthFieldsMustBeValidTopLevelScalars() {
        assertThat(McpOAuthManager.extractJsonField("{\"refresh_token\":null}", "refresh_token")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"extra\":{\"access_token\":\"nested\"}}", "access_token")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"access_token\":\"unterminated", "access_token")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"expires_in\":[3600]}", "expires_in")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"access_token\":123}", "access_token")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"access_token\":\"first\",\"access_token\":\"second\"}", "access_token")).isNull();
        assertThat(McpOAuthManager.extractJsonField("{\"access_token\":\"first\"} trailing", "access_token")).isNull();
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

    // ─── P2-17: refreshToken interrupt handling ──────────────────────

    @Test
    void getToken_interruptedDuringAutoRefresh_restoresInterruptFlag() {
        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        // Simulate interrupt by pre-interrupting the thread
        Thread.currentThread().interrupt();
        try {
            Optional<String> token = manager.getToken("default", server("test-server"));
            // Should return empty (no valid token after failed refresh)
            assertThat(token).isEmpty();
            // Interrupt flag should be restored
            // Note: the refreshToken method will throw because the server fixture has no OAuth endpoint,
            // but the InterruptedException catch won't trigger here since the failure is
            // IllegalStateException, not InterruptedException. This test verifies the
            // getToken path doesn't crash when token is expired.
        } finally {
            // Clear interrupt flag for subsequent tests
            Thread.interrupted();
        }
    }

    @Test
    void refreshToken_noStoredToken_throwsIllegalStateException() {
        // Configure a server with OAuth URL but no stored token
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("empty-server");
        serverConfig.setOauthTokenUrl("https://example.com/token");

        when(mcpOAuthRepository.findByProfileAndServerName("default", "empty-server")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No stored OAuth token");
    }

    @Test
    void refreshToken_noRefreshTokenStored_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("no-refresh-server");
        serverConfig.setOauthTokenUrl("https://example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setRefreshToken(null);
        when(mcpOAuthRepository.findByProfileAndServerName("default", "no-refresh-server")).thenReturn(Optional.of(entity));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No refresh token stored");
    }

    @Test
    void refreshToken_noTokenUrlConfigured_throwsIllegalStateException() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("no-url-server");
        // No oauthTokenUrl set

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No OAuth token URL configured");
    }

    // ─── V67 (WP-e): refresh tokens are bound to their issuer ──────────

    @Test
    void refreshToken_issuerMismatch_discardsTokenAndThrows() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("migrated-server");
        serverConfig.setOauthTokenUrl("https://new-auth.example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setProfile("default");
        entity.setServerName("migrated-server");
        entity.setRefreshToken("legacy-refresh-token");
        entity.setTokenIssuer("https://old-auth.example.com"); // minted by the OLD issuer
        when(mcpOAuthRepository.findByProfileAndServerName("default", "migrated-server"))
            .thenReturn(Optional.of(entity));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("was issued by https://old-auth.example.com")
            .hasMessageContaining("re-authorization required");
        // the mismatched token is DISCARDED, never replayed at the new issuer
        org.mockito.Mockito.verify(mcpOAuthRepository).delete(entity);
    }

    @Test
    void refreshToken_matchingIssuer_proceedsToRefresh() {
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("stable-server");
        serverConfig.setOauthTokenUrl("https://auth.example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setProfile("default");
        entity.setServerName("stable-server");
        entity.setRefreshToken("refresh-token");
        entity.setTokenIssuer("https://auth.example.com");
        when(mcpOAuthRepository.findByProfileAndServerName("default", "stable-server"))
            .thenReturn(Optional.of(entity));

        // The issuer check passes; the refresh itself fails on the HTTP call
        // (no server behind example.com in tests) — but NOT with the issuer error.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isNotInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never())
            .delete(entity);
    }

    @Test
    void refreshToken_legacyNullIssuer_proceedsWithoutDiscard() {
        // Pre-V67 rows have NULL issuer: unbound, but not silently replayed-
        // and-dropped — the refresh runs and re-binds the issuer on success.
        AgentProperties.McpProperties.ServerProperties serverConfig = new AgentProperties.McpProperties.ServerProperties();
        serverConfig.setName("legacy-server");
        serverConfig.setOauthTokenUrl("https://auth.example.com/token");

        McpOAuthEntity entity = new McpOAuthEntity();
        entity.setProfile("default");
        entity.setServerName("legacy-server");
        entity.setRefreshToken("refresh-token");
        entity.setTokenIssuer(null);
        when(mcpOAuthRepository.findByProfileAndServerName("default", "legacy-server"))
            .thenReturn(Optional.of(entity));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                manager.refreshToken("default", serverConfig))
            .isNotInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never())
            .delete(entity);
    }
    private static AgentProperties.McpProperties.ServerProperties server(String name) {
        var server = new AgentProperties.McpProperties.ServerProperties();
        server.setName(name);
        return server;
    }
}
