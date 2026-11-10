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
import java.util.concurrent.ScheduledExecutorService;
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
    private McpLifecycleManager.ConfiguredServer configured;

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
        McpServerConfigEntity configEntity = new McpServerConfigEntity();
        configEntity.setId(UUID.randomUUID());
        configEntity.setProfile("default");
        configEntity.setName("cached-server");
        configEntity.setConfigRevision(3);
        configEntity.setEnabled(true);
        configEntity.setTransport("http");
        configEntity.setBaseUrl(server.getBaseUrl());
        when(configStore.find("default", "cached-server")).thenReturn(Optional.of(configEntity));
        configured = new McpLifecycleManager.ConfiguredServer(server, configEntity);
    }

    @Test
    void cachedDefinitionsUseTheCurrentAdmissionDecision() throws Exception {
        var scanner = mock(com.azhukov.agent.core.security.McpToolDefinitionScanner.class);
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "definitionScanner", scanner);
        when(scanner.scan(eq("mcp__cached_server__allowed"), any(), any()))
            .thenReturn(com.azhukov.agent.core.security.ScanResult.clean());
        when(scanner.scan(eq("mcp__cached_server__denied"), any(), any()))
            .thenReturn(com.azhukov.agent.core.security.ScanResult.of(
                com.azhukov.agent.core.security.Severity.HIGH, "fixture decision", List.of()));
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(freshCache(
            "[{\"name\":\"allowed\"},{\"name\":\"denied\"}]")));
        when(configStore.isFresh(any())).thenReturn(true);

        manager.connect(configured);

        verify(toolRegistry).registerDynamic(eq("mcp__cached_server__allowed"), any(), any(), any());
        verify(toolRegistry, never()).registerDynamic(eq("mcp__cached_server__denied"), any(), any(), any());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true, false", "false, false", "true, true"})
    void firstLazyRpcRetiresOnlyBrokenTransport(boolean brokenTransport, boolean replaced) throws Exception {
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(freshCache("[{\"name\":\"echo\"}]")));
        when(configStore.isFresh(any())).thenReturn(true);
        var lifecycle = Mockito.spy(manager);
        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(McpSchema.Tool.builder("echo").build()), null));
        when(client.closeGracefully()).thenReturn(true);
        var replacement = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(client.callTool(any())).thenAnswer(call -> {
            if (replaced) {
                lifecycle.getClientsForTest().put("cached-server",
                    new McpLifecycleManager.McpServerState(server, replacement, List.of()));
            }
            throw new IllegalStateException(brokenTransport ? "connection reset by peer" : "invalid tool arguments");
        });
        Mockito.doReturn(client).when(lifecycle).createClient(any());
        try {
            lifecycle.connect(configured);
            assertThat(lifecycle.isConnected("cached-server")).isFalse();
            var result = lifecycle.new McpToolHandler("cached-server", "echo").execute("{}", null, null);
            assertThat(result.success()).isFalse();
            verify(client).initialize();
            verify(client).callTool(any());
            boolean retire = brokenTransport && !replaced;
            assertThat(lifecycle.isConnected("cached-server")).isEqualTo(!retire);
            if (replaced) {
                assertThat(lifecycle.getClientsForTest().get("cached-server").client()).isSameAs(replacement);
            }
            if (retire) {
                verify(client, Mockito.timeout(2000)).closeGracefully();
            } else {
                verify(client, never()).closeGracefully();
            }
        } finally {
            lifecycle.closeAll();
        }
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

        spy.connect(configured);

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
        Mockito.doReturn(client).when(lifecycle).createClient(any());
        lifecycle.connect(configured);
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

        verify(lifecycle).createClient(any());
        verify(client, org.mockito.Mockito.timeout(2000)).closeGracefully();
        verify(toolRegistry, Mockito.atLeastOnce()).deregisterDynamic("mcp__cached_server__echo", "mcp-cached-server");
        assertThat(lifecycle.isConnected("cached-server")).isFalse();
    }

    @Test
    void unavailableConfigurationLookupDoesNotStopScheduledRecovery() throws Exception {
        properties.getMcp().setLazyStartup(false);
        var lifecycle = Mockito.spy(manager);
        var scheduler = mock(ScheduledExecutorService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "reconnectExecutor", scheduler);
        var scheduled = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        when(scheduler.schedule(any(Runnable.class), Mockito.anyLong(), eq(TimeUnit.SECONDS)))
            .thenAnswer(call -> { scheduled.add(call.getArgument(0)); return null; });
        when(configStore.find("default", "cached-server"))
            .thenThrow(new IllegalStateException("database temporarily unavailable"))
            .thenReturn(Optional.of(configured.schemaConfig()));
        var failed = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(failed.initialize()).thenThrow(new IllegalStateException("fixture offline"));
        when(failed.closeGracefully()).thenReturn(true);
        var live = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        when(live.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        Mockito.doReturn(failed, live).when(lifecycle).createClient(any());

        try {
            lifecycle.connect(configured);
            var retry = scheduled.poll(2, TimeUnit.SECONDS);
            assertThat(retry).isNotNull();
            retry.run();
            assertThat(lifecycle.isConnected("cached-server")).isFalse();
            assertThat(scheduled).hasSize(1);
            scheduled.remove().run();
            assertThat(lifecycle.isConnected("cached-server")).isTrue();
            verify(live).initialize();
        } finally {
            lifecycle.closeAll();
        }
    }

    @Test
    void lazyWaitExpiresWithoutCancellingSharedHandshake() throws Exception {
        configured.schemaConfig().setTimeoutSeconds(1);
        var lifecycle = Mockito.spy(manager);
        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var cache = freshCache("[{\"name\":\"echo\"}]");
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(cache));
        when(configStore.isFresh(cache)).thenReturn(true);
        Mockito.doReturn(client).when(lifecycle).createClient(any());
        var release = new CountDownLatch(1);
        when(client.initialize()).thenAnswer(call -> {
            assertThat(release.await(4, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(McpSchema.Tool.builder("echo").build()), null));
        var result = mock(McpSchema.CallToolResult.class);
        when(client.callTool(any())).thenReturn(result);

        lifecycle.connect(configured);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> lifecycle.executeTool("cached-server", "echo", "{}"))
                .hasMessageContaining("Timed out connecting");
            verify(client, never()).callTool(any());
            release.countDown();
            assertThat(lifecycle.executeTool("cached-server", "echo", "{}")).isSameAs(result);
            verify(client).initialize();
        } finally {
            release.countDown();
            lifecycle.closeAll();
        }
    }

    @Test
    void lazyCallerFailsDuringBackoffInsteadOfJoiningRecovery() throws Exception {
        var lifecycle = Mockito.spy(manager);
        var scheduler = mock(ScheduledExecutorService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(lifecycle, "reconnectExecutor", scheduler);
        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var cache = freshCache("[{\"name\":\"echo\"}]");
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.of(cache));
        when(configStore.isFresh(cache)).thenReturn(true);
        Mockito.doReturn(client).when(lifecycle).createClient(any());
        when(client.initialize()).thenThrow(new IllegalStateException("fixture offline"));
        when(client.closeGracefully()).thenReturn(true);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lifecycle.connect(configured);
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lifecycle.executeTool("cached-server", "echo", "{}"))
                    .hasMessageContaining("Failed to connect");
                verify(scheduler, Mockito.timeout(2000)).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
                var second = executor.submit(() -> org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lifecycle.executeTool("cached-server", "echo", "{}"))
                    .hasMessageContaining("waiting to reconnect"));
                second.get(2, TimeUnit.SECONDS);
                verify(client).initialize();
            } finally {
                lifecycle.closeAll();
            }
        }
    }

    @Test
    void schemaObservationsAreOrderedBeforeWrites() throws Exception {
        properties.getMcp().setLazyStartup(false);
        var lifecycle = Mockito.spy(manager);
        var oldClient = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var newClient = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var oldTool = McpSchema.Tool.builder("old_manifest_tool").inputSchema(Map.of("type", "object")).build();
        var newTool = McpSchema.Tool.builder("new_manifest_tool").inputSchema(Map.of("type", "object")).build();
        when(oldClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(oldTool), null));
        when(newClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(newTool), null));
        when(oldClient.closeGracefully()).thenReturn(true);
        when(newClient.closeGracefully()).thenReturn(true);
        Mockito.doReturn(oldClient, newClient).when(lifecycle).createClient(any());

        var oldWrite = new CountDownLatch(1);
        var newWrite = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstObservedAt = new java.util.concurrent.atomic.AtomicReference<Instant>();
        var nextObservedAt = new java.util.concurrent.atomic.AtomicReference<Instant>();
        Mockito.doAnswer(call -> {
            Instant observedAt = call.getArgument(2);
            String json = call.getArgument(3);
            if (json.contains("old_manifest_tool")) {
                firstObservedAt.set(observedAt);
                oldWrite.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                nextObservedAt.set(observedAt);
                newWrite.countDown();
            }
            return null;
        }).when(configStore).storeSchema(eq(configured.schemaConfig().getId()), eq(3L), any(), any(), any(), any());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> lifecycle.connect(configured));
            try {
                assertThat(oldWrite.await(2, TimeUnit.SECONDS)).isTrue();
                lifecycle.reconnect("cached-server");
                assertThat(newWrite.await(4, TimeUnit.SECONDS)).isTrue();
                assertThat(lifecycle.getClientsForTest().get("cached-server").client()).isSameAs(newClient);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertThat(firstObservedAt.get()).isBefore(nextObservedAt.get());
        } finally {
            lifecycle.closeAll();
        }
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
        }).when(configStore).storeSchema(any(), eq(3L), any(), any(), any(), any());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> lifecycle.connect(configured));
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
        manager.connect(configured);

        assertThat(manager.isConnected("cached-server")).isFalse();
        verify(configStore).recordSchemaError(any(), eq(3L), any(), any());
    }

    @Test
    void configRevisionMismatchIsAMiss() {
        when(configStore.cachedSchema(any(), eq(3L))).thenReturn(Optional.empty());

        manager.connect(configured);

        // Cache miss (revision changed) — lazy registration declined; the full
        // connect path runs (and fails against localhost:9999).
        verify(configStore, never()).storeSchema(any(), org.mockito.ArgumentMatchers.anyLong(),
            any(), any(), any(), any());
    }
}
