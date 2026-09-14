package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.persistence.repository.McpSchemaCacheRepository;
import com.azhukov.agent.persistence.repository.McpServerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
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
 * WP-3: persisted MCP config store validation + durable schema cache
 * (single-flight store, stale-keep on error, revision invalidation).
 */
@ExtendWith(MockitoExtension.class)
class McpConfigStoreTest {

    @Mock
    private McpServerConfigRepository configRepository;

    @Mock
    private McpSchemaCacheRepository schemaCacheRepository;

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

    private McpConfigStore store() {
        return new McpConfigStore(prov(configRepository), prov(schemaCacheRepository),
            new ObjectMapper());
    }

    private McpConfigStore.ServerConfigInput input(String name, String transport, String command, String baseUrl) {
        return new McpConfigStore.ServerConfigInput(name, true, transport, command,
            List.of("-y", "server"), baseUrl, List.of("SOME_KEY"), Map.of("X-Api", "v1"),
            List.of("tool1"), List.of(), 30.0, "full", null, null, null);
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void stdioRequiresCommand() {
        assertThatThrownBy(() -> store().upsert("default", input("srv", "stdio", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stdio transport requires a command");
    }

    @Test
    void httpRequiresBaseUrl() {
        assertThatThrownBy(() -> store().upsert("default", input("srv", "http", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires a base_url");
    }

    @Test
    void unknownTransportRejected() {
        assertThatThrownBy(() -> store().upsert("default", input("srv", "grpc", "cmd", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported transport: grpc");
    }

    @Test
    void unknownTrustRejected() {
        var bad = new McpConfigStore.ServerConfigInput("srv", true, "stdio", "cmd",
            List.of(), null, List.of(), Map.of(), List.of(), List.of(), 0, "partial", null, null, null);
        assertThatThrownBy(() -> store().upsert("default", bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported trust tier: partial");
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> store().upsert("default", input("", "stdio", "cmd", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name is required");
    }

    // ── upsert / revision ────────────────────────────────────────────────

    @Test
    void upsertBumpsRevisionAndInvalidatesCache() {
        McpServerConfigEntity existing = new McpServerConfigEntity();
        existing.setId(UUID.randomUUID());
        existing.setProfile("default");
        existing.setName("srv");
        existing.setConfigRevision(3);
        when(configRepository.findByProfileAndName("default", "srv")).thenReturn(Optional.of(existing));
        when(configRepository.save(any(McpServerConfigEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        McpServerConfigEntity saved = store().upsert("default", input("srv", "stdio", "cmd", null));

        assertThat(saved.getConfigRevision()).isEqualTo(4);
        verify(schemaCacheRepository).deleteByServerConfigId(existing.getId());
    }

    @Test
    void redactedViewNeverExposesEnvValuesOrSecrets() {
        McpServerConfigEntity entity = new McpServerConfigEntity();
        entity.setProfile("default");
        entity.setName("srv");
        entity.setTransport("stdio");
        entity.setCommand("npx");
        entity.setEnvKeysJson("[\"SECRET_API_KEY\"]");
        entity.setOauthTokenUrl("https://oauth.example/token");
        entity.setOauthClientId("client-1");

        Map<String, Object> view = store().redactedView(entity);

        assertThat(view).containsEntry("oauth_configured", true);
        // OAuth client id/secret/token url never round-trip; env shows KEY names only.
        assertThat(view.keySet()).doesNotContain("oauth_client_id", "oauth_client_secret",
            "oauth_token_url", "env");
        assertThat((List<String>) view.get("env_keys")).containsExactly("SECRET_API_KEY");
    }

    // ── schema cache ─────────────────────────────────────────────────────

    @Test
    void staleSchemaSurvivesFailedRefresh() {
        UUID configId = UUID.randomUUID();
        McpSchemaCacheEntity cached = new McpSchemaCacheEntity();
        cached.setServerConfigId(configId);
        cached.setConfigRevision(1);
        cached.setToolsJson("[{\"name\":\"tool1\"}]");
        when(schemaCacheRepository.findFirstByServerConfigIdOrderByFetchedAtDesc(configId))
            .thenReturn(Optional.of(cached));
        when(schemaCacheRepository.save(any(McpSchemaCacheEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        store().recordSchemaError(configId, 1, "connection refused");

        assertThat(cached.getToolsJson()).isNotNull(); // prior schema kept
        assertThat(cached.getLastError()).isEqualTo("connection refused");
        verify(schemaCacheRepository, never()).deleteByServerConfigId(configId);
    }

    @Test
    void cacheReadIsRevisionScoped() {
        UUID configId = UUID.randomUUID();
        McpSchemaCacheEntity cached = new McpSchemaCacheEntity();
        cached.setServerConfigId(configId);
        cached.setConfigRevision(1);
        when(schemaCacheRepository.findFirstByServerConfigIdOrderByFetchedAtDesc(configId))
            .thenReturn(Optional.of(cached));

        assertThat(store().cachedSchema(configId, 1)).isPresent();
        assertThat(store().cachedSchema(configId, 2)).isEmpty(); // config changed
    }

    @Test
    void storeSchemaIsSingleFlightAndSetsTtl() {
        UUID configId = UUID.randomUUID();
        when(schemaCacheRepository.findFirstByServerConfigIdOrderByFetchedAtDesc(configId))
            .thenReturn(Optional.empty());
        when(schemaCacheRepository.save(any(McpSchemaCacheEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        store().storeSchema(configId, 2, "[{\"name\":\"t\"}]", "[]", "[]");

        verify(schemaCacheRepository).save(any(McpSchemaCacheEntity.class));
        McpSchemaCacheEntity saved = org.mockito.Mockito.mockingDetails(schemaCacheRepository)
            .getInvocations().stream()
            .filter(inv -> "save".equals(inv.getMethod().getName()))
            .map(inv -> (McpSchemaCacheEntity) inv.getArgument(0))
            .findFirst().orElseThrow();
        assertThat(saved.getConfigRevision()).isEqualTo(2);
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(saved.getLastError()).isNull();
    }
}
