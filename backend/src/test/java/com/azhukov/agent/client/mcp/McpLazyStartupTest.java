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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void concurrentFirstCallsShareOneHandshakeAndReloadWithdrawsTools() throws Exception {
        String toolsJson = objectMapper.writeValueAsString(List.of(
            Map.of("name", "echo", "description", "Echo a value",
                "inputSchema", Map.of("type", "object", "properties", Map.of()))));
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(freshCache(toolsJson)));
        when(configStore.isFresh(any())).thenReturn(true);

        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var connected = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(client.initialize()).thenAnswer(call -> {
            connected.countDown();
            assertThat(release.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return null;
        });
        var tool = McpSchema.Tool.builder("echo").description("Echo a value")
            .inputSchema(Map.of("type", "object")).build();
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        var result = mock(McpSchema.CallToolResult.class);
        when(client.callTool(any())).thenReturn(result);

        var lifecycle = Mockito.spy(manager);
        Mockito.doReturn(client).when(lifecycle).createClient(server);
        lifecycle.connect(server);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> lifecycle.executeTool("cached-server", "echo", "{}"));
            assertThat(connected.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> lifecycle.executeTool("cached-server", "echo", "{}"));
            release.countDown();
            assertThat(first.get(2, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(result);
            assertThat(second.get(2, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(result);
        } finally {
            lifecycle.closeAll();
        }

        verify(lifecycle).createClient(server);
        verify(client).close();
        verify(toolRegistry, Mockito.atLeastOnce()).deregisterDynamic("mcp__cached_server__echo", "mcp-cached-server");
        assertThat(lifecycle.isConnected("cached-server")).isFalse();
    }

    @Test
    void slowSchemaStoreDoesNotLockOtherConnections() throws Exception {
        properties.getMcp().setLazyStartup(false);
        var neighbour = new AgentProperties.McpProperties.ServerProperties();
        neighbour.setName("neighbour");
        var lifecycle = Mockito.spy(manager);
        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var otherClient = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        when(otherClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        Mockito.doReturn(client).when(lifecycle).createClient(server);
        Mockito.doReturn(otherClient).when(lifecycle).createClient(neighbour);
        var storing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Mockito.doAnswer(call -> {
            storing.countDown();
            release.await();
            return null;
        }).when(configStore).storeSchema(any(), eq(3L), any(), any(), any());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> lifecycle.connect(server));
            try {
                assertThat(storing.await(5, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> lifecycle.connect(neighbour)).get(5, TimeUnit.SECONDS);
                assertThat(lifecycle.isConnected("neighbour")).isTrue();
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        } finally {
            lifecycle.closeAll();
        }
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
