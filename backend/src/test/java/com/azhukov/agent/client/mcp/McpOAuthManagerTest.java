package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
            entity.setTokenIssuer(McpOAuthManager.issuerOf(config.getOauthTokenUrl()));
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
            entity.setTokenIssuer(McpOAuthManager.issuerOf(config.getOauthTokenUrl()));
            when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.refreshToken("default", config))
                .isInstanceOf(java.io.IOException.class);
            org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never()).save(any());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void malformedProviderBodyDoesNotExposeSecretsInException() throws Exception {
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"sensitive-canary\", broken}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        http.start();
        try {
            var config = server("test-server");
            config.setOauthTokenUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/token");
            var entity = new McpOAuthEntity(); entity.setRefreshToken("old-refresh");
            entity.setTokenIssuer(McpOAuthManager.issuerOf(config.getOauthTokenUrl()));
            when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.refreshToken("default", config))
                .isInstanceOf(java.io.IOException.class).hasMessageNotContaining("sensitive-canary");
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
        entity.setTokenIssuer("https://mcp.example.com");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        Optional<String> token = manager.getToken("default", server("test-server"));

        assertThat(token).isPresent();
        assertThat(token.get()).isEqualTo("access-123");
    }

    @Test
    void getTokenAcceptsExistingIssuerWithDifferentHostnameCase() {
        var entity = new McpOAuthEntity();
        entity.setAccessToken("access-123");
        entity.setTokenIssuer("https://MCP.Example.com");
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));

        assertThat(manager.getToken("default", server("test-server"))).contains("access-123");
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
        config.setOauthTokenUrl(null);
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
    void invalidEndpointIsRejectedBeforeCredentialMutation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            manager.storeToken("research", "test-server", "access", "refresh", null, "not an endpoint"))
            .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(mcpOAuthRepository);
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

    @Test
    void getToken_interruptedDuringAutoRefresh_restoresInterruptFlag() throws Exception {
        var entity = new McpOAuthEntity();
        entity.setAccessToken("expired");
        entity.setTokenIssuer("https://mcp.example.com");
        entity.setExpiresAt(Instant.now().minusSeconds(3600));
        when(mcpOAuthRepository.findByProfileAndServerName("default", "test-server")).thenReturn(Optional.of(entity));
        var interrupted = org.mockito.Mockito.spy(manager);
        var config = server("test-server");
        org.mockito.Mockito.doThrow(new InterruptedException()).when(interrupted).refreshToken("default", config);
        try {
            assertThat(interrupted.getToken("default", config)).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
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
            .hasMessageContaining("has no matching issuer")
            .hasMessageContaining("re-authorization required");
        // the mismatched token is DISCARDED, never replayed at the new issuer
        org.mockito.Mockito.verify(mcpOAuthRepository).delete(entity);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "https://other.example.com"})
    void unboundOrForeignIssuerCannotReadOrRefreshCredential(String issuer) throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        http.start();
        try {
            var config = server("renamed-connection");
            config.setOauthTokenUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/token");
            var entity = new McpOAuthEntity();
            entity.setAccessToken("unbound-access");
            entity.setRefreshToken("unbound-refresh");
            entity.setTokenIssuer(issuer);
            when(mcpOAuthRepository.findByProfileAndServerName("research", "renamed-connection"))
                .thenReturn(Optional.of(entity));

            for (var expiry : new Instant[] {null, Instant.now().plusSeconds(3600), Instant.now().minusSeconds(3600)}) {
                entity.setExpiresAt(expiry);
                assertThat(manager.getToken("research", config)).isEmpty();
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.refreshToken("research", config))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("re-authorization required");
            assertThat(requests.get()).isZero();
            org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never()).save(any());
        } finally {
            http.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void matchingIssuerRefreshUsesSelectedProfileAndFormCredentials(boolean withClientCredentials) throws Exception {
        var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"new-access\",\"expires_in\":3600}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        http.start();
        try {
            var config = server("custom-connection");
            config.setOauthTokenUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/token");
            if (withClientCredentials) {
                config.setOauthClientId("client id");
                config.setOauthClientSecret("client&secret");
                config.setOauthScopes("read write");
            }
            var entity = new McpOAuthEntity();
            entity.setAccessToken("expired");
            entity.setRefreshToken("refresh&token");
            entity.setExpiresAt(Instant.now().minusSeconds(60));
            entity.setTokenIssuer(McpOAuthManager.issuerOf(config.getOauthTokenUrl()));
            when(mcpOAuthRepository.findByProfileAndServerName("research", "custom-connection"))
                .thenReturn(Optional.of(entity));

            assertThat(manager.getToken("research", config)).contains("new-access");
            assertThat(requestBody.get()).contains("grant_type=refresh_token", "refresh_token=refresh%26token");
            if (withClientCredentials) {
                assertThat(requestBody.get()).contains("client_id=client+id", "client_secret=client%26secret", "scope=read+write");
            } else {
                assertThat(requestBody.get()).doesNotContain("client_id=", "client_secret=", "scope=");
            }
            verify(mcpOAuthRepository).save(entity);
            assertThat(entity.getProfile()).isEqualTo("research");
            assertThat(entity.getRefreshToken()).isEqualTo("refresh&token");
            assertThat(entity.getTokenIssuer()).isEqualTo(McpOAuthManager.issuerOf(config.getOauthTokenUrl()));
            org.mockito.Mockito.verify(mcpOAuthRepository, org.mockito.Mockito.never()).findByProfileAndServerName("default", "custom-connection");
        } finally {
            http.stop(0);
        }
    }

    private static AgentProperties.McpProperties.ServerProperties server(String name) {
        var server = new AgentProperties.McpProperties.ServerProperties();
        server.setName(name);
        server.setOauthTokenUrl("https://mcp.example.com/oauth/token");
        return server;
    }
}
