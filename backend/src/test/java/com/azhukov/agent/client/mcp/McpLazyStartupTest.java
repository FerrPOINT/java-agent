package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.service.McpConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermes #56832 lazy startup parity: a fresh persisted schema cache lets a
 * server register its tools WITHOUT spawning the server process; the first
 * real tool call connects on demand.
 */
class McpLazyStartupTest {

    private final AgentProperties properties = new AgentProperties();
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final McpConfigStore configStore = mock(McpConfigStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpLifecycleManager manager;
    private AgentProperties.McpProperties.ServerProperties server;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(toolRegistry);
        ObjectProvider<McpConfigStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configStore);

        manager = new McpLifecycleManager(properties, objectMapper, ctx,
            null, null, null, null, null, provider);

        server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("cached-server");
        server.setTransport("http");
        server.setBaseUrl("http://localhost:9999");
        properties.getMcp().setEnabled(true);
        properties.getMcp().getServers().add(server);

        McpServerConfigEntity configEntity = new McpServerConfigEntity();
        configEntity.setId(UUID.randomUUID());
        configEntity.setProfile("default");
        configEntity.setName("cached-server");
        configEntity.setConfigRevision(3);
        when(configStore.find("default", "cached-server")).thenReturn(Optional.of(configEntity));
    }

    private McpSchemaCacheEntity freshCache(String toolsJson) throws Exception {
        McpSchemaCacheEntity cache = new McpSchemaCacheEntity();
        cache.setServerConfigId(UUID.randomUUID());
        cache.setConfigRevision(3);
        cache.setToolsJson(toolsJson);
        cache.setFetchedAt(Instant.now());
        cache.setExpiresAt(Instant.now().plusSeconds(3600));
        return cache;
    }

    @Test
    void freshCacheRegistersWithoutConnecting() throws Exception {
        String toolsJson = objectMapper.writeValueAsString(List.of(
            Map.of("name", "echo", "description", "Echo a value",
                "inputSchema", Map.of("type", "object", "properties", Map.of()))));
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(freshCache(toolsJson)));
        when(configStore.isFresh(any())).thenReturn(true);

        McpLifecycleManager spy = Mockito.spy(manager);
        Mockito.doThrow(new AssertionError("connect must not spawn on a cache hit"))
            .when(spy).createClient(Mockito.any());

        spy.connect(server);

        verify(toolRegistry).registerDynamic(
            eq("mcp__cached_server__echo"), eq("mcp-cached-server"), any(), any());
        assertThat(spy.isConnected("cached-server")).isFalse();
    }

    @Test
    void staleCacheFallsThroughToConnect() throws Exception {
        String toolsJson = objectMapper.writeValueAsString(List.of());
        McpSchemaCacheEntity stale = freshCache(toolsJson);
        stale.setExpiresAt(Instant.now().minusSeconds(60));
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(stale));
        when(configStore.isFresh(any())).thenReturn(false);

        // No HTTP server runs at localhost:9999 — the connect attempt fails and
        // the error is recorded in the cache WITHOUT deleting the schema.
        manager.connect(server);

        assertThat(manager.isConnected("cached-server")).isFalse();
        verify(configStore).recordSchemaError(any(), eq(3L), any());
    }

    @Test
    void configRevisionMismatchIsAMiss() {
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.empty());

        manager.connect(server);

        // Cache miss (revision changed) — lazy registration declined; the full
        // connect path runs (and fails against localhost:9999).
        verify(configStore, never()).storeSchema(any(), org.mockito.ArgumentMatchers.anyLong(),
            any(), any(), any());
    }
}
