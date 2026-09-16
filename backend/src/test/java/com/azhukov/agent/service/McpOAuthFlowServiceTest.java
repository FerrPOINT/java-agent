package com.azhukov.agent.service;

import com.azhukov.agent.client.mcp.McpOAuthManager;
import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.persistence.repository.McpOAuthFlowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-3 OAuth Authorization Code + PKCE flow: state validation, single-use,
 * expiry, encrypted verifier, token exchange failures never leak values.
 */
@ExtendWith(MockitoExtension.class)
class McpOAuthFlowServiceTest {

    @Mock
    private McpOAuthFlowRepository flowRepository;

    @Mock
    private McpConfigStore configStore;

    @Mock
    private McpOAuthManager oauthManager;

    private final TokenSecretCipher cipher = new TokenSecretCipher("test-master-secret");

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private McpOAuthFlowService service() {
        return new McpOAuthFlowService(prov(flowRepository), prov(configStore),
            prov(cipher), prov(oauthManager));
    }

    private McpServerConfigEntity config(String name) {
        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setProfile("default");
        entity.setName(name);
        entity.setTransport("http");
        entity.setBaseUrl("https://mcp.example.com/mcp");
        entity.setOauthTokenUrl("https://mcp.example.com/oauth/token");
        entity.setOauthClientId("client-1");
        return entity;
    }

    private McpOAuthFlowEntity pendingFlow(String serverName) {
        McpOAuthFlowEntity flow = new McpOAuthFlowEntity();
        flow.setId(UUID.randomUUID());
        flow.setProfile("default");
        flow.setServerName(serverName);
        flow.setStateHash("hash");
        flow.setCodeChallenge("challenge");
        flow.setChallengeMethod("S256");
        flow.setVerifierEncrypted(cipher.encrypt("verifier"));
        flow.setRedirectUri("https://local/api/mcp/oauth/callback/" + serverName);
        flow.setAuthorizationUrl("https://mcp.example.com/oauth/authorize");
        flow.setExpiresAt(Instant.now().plusSeconds(600));
        flow.setStatus("pending");
        return flow;
    }

    @Test
    void startRejectsUnconfiguredServer() {
        when(configStore.find("default", "ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().start("default", "ghost", "https://local"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not configured");
    }

    @Test
    void startRejectsServerWithoutOauthMetadata() {
        McpServerConfigEntity entity = config("plain");
        entity.setOauthTokenUrl(null);
        when(configStore.find("default", "plain")).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service().start("default", "plain", "https://local"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no OAuth metadata");
    }

    @Test
    void startCreatesPendingFlowWithS256AndEncryptedVerifier() {
        when(configStore.find("default", "srv")).thenReturn(Optional.of(config("srv")));
        when(flowRepository.save(any(McpOAuthFlowEntity.class)))
            .thenAnswer(inv -> {
                McpOAuthFlowEntity saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });

        var started = service().start("default", "srv", "https://local");

        assertThat(started.authorizationUrl()).contains("code_challenge_method=S256");
        assertThat(started.authorizationUrl()).contains("response_type=code");
        assertThat(started.authorizationUrl()).doesNotContain("client_secret");
        verify(flowRepository).save(any(McpOAuthFlowEntity.class));
    }

    @Test
    void completeRejectsUnknownState() {
        Map<String, Object> result = service().complete("srv", "code", "unknown-state");
        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("detail"))).contains("unknown or already used");
    }

    @Test
    void completeRejectsMissingParams() {
        Map<String, Object> result = service().complete("srv", null, null);
        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("detail"))).contains("missing state or code");
    }

    @Test
    void completeRejectsExpiredFlow() {
        McpOAuthFlowEntity flow = pendingFlow("srv");
        flow.setExpiresAt(Instant.now().minusSeconds(1));
        when(flowRepository.findByStateHash(any(String.class))).thenReturn(Optional.of(flow));
        when(flowRepository.save(any(McpOAuthFlowEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service().complete("srv", "code", "state");

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("detail"))).contains("expired");
        assertThat(flow.getStatus()).isEqualTo("expired");
    }

    @Test
    void completeRejectsWrongServer() {
        McpOAuthFlowEntity flow = pendingFlow("other");
        when(flowRepository.findByStateHash(any(String.class))).thenReturn(Optional.of(flow));
        when(flowRepository.save(any(McpOAuthFlowEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service().complete("srv", "code", "state");

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("detail"))).contains("does not match");
        assertThat(flow.getStatus()).isEqualTo("failed");
    }

    @Test
    void completeRejectsNonPendingFlow() {
        McpOAuthFlowEntity flow = pendingFlow("srv");
        flow.setStatus("completed");
        when(flowRepository.findByStateHash(any(String.class))).thenReturn(Optional.of(flow));

        Map<String, Object> result = service().complete("srv", "code", "state");

        assertThat(result).containsEntry("ok", false);
        assertThat(String.valueOf(result.get("detail"))).contains("already used");
        verify(oauthManager, never()).storeToken(any(), any(), any(), any());
    }

    @Test
    void cancelOnlyPendingFlows() {
        McpOAuthFlowEntity flow = pendingFlow("srv");
        when(flowRepository.findById(flow.getId())).thenReturn(Optional.of(flow));
        when(flowRepository.save(any(McpOAuthFlowEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().cancel(flow.getId().toString())).isTrue();
        assertThat(flow.getStatus()).isEqualTo("cancelled");

        assertThat(service().cancel(flow.getId().toString())).isFalse(); // no longer pending
    }

    @Test
    void cipherRoundTripAndFailClosed() {
        String plaintext = "secret-verifier-value";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);

        // tampered ciphertext must fail, not return garbage
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cipherWithoutSecretFailsClosed() {
        assertThatThrownBy(() -> new TokenSecretCipher(""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("encryption-secret");
    }
}
