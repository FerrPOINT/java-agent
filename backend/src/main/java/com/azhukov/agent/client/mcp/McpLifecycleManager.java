package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
import com.azhukov.agent.core.security.McpToolTrustService;
import com.azhukov.agent.core.security.McpToolTrustService.ToolTrustMetadata;
import com.azhukov.agent.core.security.OsvCheckService;
import com.azhukov.agent.core.security.ScanResult;
import com.azhukov.agent.core.security.Severity;
import com.azhukov.agent.core.security.SlidingWindowRateLimiter;
import com.azhukov.agent.core.security.ToolArgumentInjectionScanner;
import com.azhukov.agent.core.security.ToolFingerprintStore;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpLifecycleManager {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final McpToolDefinitionScanner definitionScanner;
    private final McpResponseScanner responseScanner;
    private final ToolArgumentInjectionScanner argumentScanner;
    private final ToolFingerprintStore fingerprintStore;
    private final SlidingWindowRateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    private com.azhukov.agent.service.AttachmentArtifactService artifacts;

    @FunctionalInterface
    interface MediaPublisher {
        String publish(String disposition, String mimeType, String name, byte[] bytes);
    }

    /**
     * Lazy startup seam (Hermes #56832 / tools/mcp_schema_cache.py parity):
     * the durable schema cache turns a cold boot into a no-spawn registration.
     * ObjectProvider — the lifecycle manager must stay constructible in unit
     * tests without a database.
     */
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.McpConfigStore> configStoreProvider;
    /** Derived: OSV malware gate for stdio MCP servers (null when disabled). Created in init() so tests can inject their own via a visible field. */
    private OsvCheckService osvCheckService;
    private final Map<String, McpServerState> clients = new ConcurrentHashMap<>();
    // Guarded by clients. A reload revokes tokens before accepting new connections.
    private final Map<String, ConnectionAttempt> connectionsInFlight = new java.util.HashMap<>();

    // Access under the clients monitor. A reservation in backoff has no handshake to join.
    private static final class ConnectionAttempt {
        private CompletableFuture<Void> handshake;
        private boolean connecting;
        private McpSyncClient retiringClient;
        private boolean closing;
    }

    /** Servers registered lazily from the schema cache; the first call connects them. */
    private final Map<String, List<McpSchema.Tool>> lazyServers = new ConcurrentHashMap<>();
    @Autowired(required = false)
    private McpOAuthManager mcpOAuthManager;
    @Autowired(required = false)
    private McpToolTrustService mcpToolTrustService;

    // ── Reconnection with exponential backoff ────────────────────────────
    private static final int MAX_RECONNECT_RETRIES = 5;
    private static final int MAX_INITIAL_CONNECT_RETRIES = 3;
    private static final long MAX_BACKOFF_SECONDS = 60;

    // ── Dynamic tool refresh interval ────────────────────────────────────
    private static final long TOOL_REFRESH_INTERVAL_SECONDS = 300;

    // ── Env var filtering for stdio subprocesses ─────────────────────────
    private static final Set<String> SAFE_ENV_KEYS = Set.of(
        "PATH", "HOME", "USER", "LANG", "LC_ALL", "TERM", "SHELL", "TMPDIR"
    );

    // ── Credential stripping in error messages ───────────────────────────
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(?:"
        + "ghp_[A-Za-z0-9_]{1,255}"           // GitHub PAT
        + "|sk-[A-Za-z0-9_]{1,255}"           // OpenAI-style key
        + "|Bearer\\s+\\S+"                    // Bearer token
        + "|token=[^\\s&,;\"']{1,255}"         // token=...
        + "|key=[^\\s&,;\"']{1,255}"           // key=...
        + "|API_KEY=[^\\s&,;\"']{1,255}"       // API_KEY=...
        + "|password=[^\\s&,;\"']{1,255}"      // password=...
        + "|secret=[^\\s&,;\"']{1,255}"        // secret=...
        + ")",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MCP_NAME_COMPONENT_UNSAFE = Pattern.compile("[^A-Za-z0-9_]");
    static final String MCP_TOOL_NAME_PREFIX = "mcp__";
    private static final String MCP_NAME_DELIMITER = "__";
    private static final int MAX_TOOL_ERROR_CHARS = 2048;
    static final int MCP_HARD_RESULT_CAP_CHARS = 2_000_000;
    static final int MCP_RESOURCE_MAX_BYTES = 50 * 1024 * 1024;
    static final int MCP_RESOURCE_MAX_B64_CHARS = MCP_RESOURCE_MAX_BYTES * 4 / 3 + 4;
    static final int MCP_CIRCUIT_BREAKER_THRESHOLD = 3;
    static final Duration MCP_CIRCUIT_BREAKER_COOLDOWN = Duration.ofSeconds(60);
    static final double DEFAULT_MCP_TOOL_TIMEOUT_SECONDS = 300.0;

    private volatile ScheduledExecutorService reconnectExecutor = newReconnectExecutor();
    private volatile ScheduledExecutorService toolRefreshExecutor = newToolRefreshExecutor();
    private volatile ExecutorService toolCallExecutor = newToolCallExecutor();
    private final Map<String, Integer> mcpServerErrorCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> mcpServerBreakerOpenedAtNanos = new ConcurrentHashMap<>();

    private static ScheduledExecutorService newReconnectExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "mcp-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    private static ScheduledExecutorService newToolRefreshExecutor() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "mcp-tool-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    private static ExecutorService newToolCallExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-tool-call");
            t.setDaemon(true);
            return t;
        });
    }

    private ToolRegistry toolRegistry() {
        return applicationContext.getBean(ToolRegistry.class);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        connectConfiguredServers();
    }

    /** Derived-field init per repo convention (AGENTS.md): OSV gate is created after construction. */
    @PostConstruct
    void init() {
        boolean osvEnabled = properties != null && properties.getMcp() != null
            && properties.getMcp().isOsvCheckEnabled();
        this.osvCheckService = osvEnabled ? new OsvCheckService(true) : null;
    }

    /** Test seam: inject a stub OSV service (same package, tests only). */
    void setOsvCheckServiceForTesting(OsvCheckService service) {
        this.osvCheckService = service;
    }

    /** Test seam: inspect the derived OSV gate state. */
    OsvCheckService osvGate() {
        return osvCheckService;
    }

    /** Test seam: connection state by server name. */
    boolean isConnected(String serverName) {
        synchronized (clients) {
            return clients.containsKey(serverName);
        }
    }

    synchronized void connectConfiguredServers() {
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP is disabled.");
            return;
        }
        shutdownRequested.set(false);
        for (var server : effectiveServers()) {
            if (!server.properties().isEnabled()) {
                log.info("Skipping disabled MCP server {}", server.properties().getName());
                continue;
            }
            connect(server);
        }
    }

    record ConfiguredServer(AgentProperties.McpProperties.ServerProperties properties,
                            McpServerConfigEntity schemaConfig) {}

    /** YAML owns duplicate names. Dashboard-only servers retain their source revision. */
    List<ConfiguredServer> effectiveServers() {
        List<ConfiguredServer> merged = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (AgentProperties.McpProperties.ServerProperties server : properties.getMcp().getServers()) {
            merged.add(new ConfiguredServer(server, null));
            names.add(server.getName());
        }
        var store = configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
        if (store != null) {
            try {
                for (var entity : store.list("default")) {
                    if (entity.getName() != null && names.add(entity.getName())) {
                        merged.add(new ConfiguredServer(toServerProperties(entity), entity));
                    }
                }
            } catch (Exception e) {
                log.debug("Persisted MCP configs unavailable: {}", e.getMessage());
            }
        }
        return merged;
    }

    private AgentProperties.McpProperties.ServerProperties toServerProperties(
            McpServerConfigEntity entity) {
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setEnabled(entity.isEnabled());
        server.setName(entity.getName());
        server.setTransport(entity.getTransport() == null ? "stdio" : entity.getTransport());
        server.setCommand(entity.getCommand() == null ? "" : entity.getCommand());
        server.getArgs().addAll(parseJsonStringList(entity.getArgsJson()));
        server.getHeaders().putAll(parseJsonStringMap(entity.getHeadersJson()));
        List<String> include = parseJsonStringList(entity.getIncludeToolsJson());
        if (!include.isEmpty()) {
            server.getTools().setInclude(include);
        }
        List<String> exclude = parseJsonStringList(entity.getExcludeToolsJson());
        if (!exclude.isEmpty()) {
            server.getTools().setExclude(exclude);
        }
        server.setBaseUrl(entity.getBaseUrl() == null ? "" : entity.getBaseUrl());
        server.setTimeoutSeconds((int) entity.getTimeoutSeconds());
        server.setTrust(entity.getTrust() == null ? "full" : entity.getTrust());
        server.setOauthTokenUrl(entity.getOauthTokenUrl() == null ? "" : entity.getOauthTokenUrl());
        server.setOauthClientId(entity.getOauthClientId() == null ? "" : entity.getOauthClientId());
        server.setOauthScopes(entity.getOauthScopes() == null ? "" : entity.getOauthScopes());
        return server;
    }

    private List<String> parseJsonStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> parseJsonStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    public void connect(AgentProperties.McpProperties.ServerProperties server) {
        connect(new ConfiguredServer(server, null));
    }

    void connect(ConfiguredServer server) {
        connect(server, true, 0);
    }

    /** @param allowLazy false forces a real connect after cached registration. */
    private void connect(ConfiguredServer server, boolean allowLazy, long startedAt) {
        String name = server.properties().getName();
        ConnectionAttempt owner;
        CompletableFuture<Void> handshake;
        synchronized (clients) {
            if (shutdownRequested.get() || clients.containsKey(name)
                    || (allowLazy && lazyServers.containsKey(name))) {
                return;
            }
            var existing = connectionsInFlight.get(name);
            if (existing == null) {
                owner = new ConnectionAttempt();
                handshake = new CompletableFuture<>();
                owner.handshake = handshake;
                connectionsInFlight.put(name, owner);
                if (!allowLazy) {
                    toolCallExecutor.execute(() -> connectAttempt(server, owner, handshake, false, 0, true));
                }
            } else {
                if (allowLazy) {
                    closeRetiredClient(name, existing);
                    return;
                }
                owner = existing;
                handshake = owner.handshake;
            }
        }
        if (allowLazy) {
            connectAttempt(server, owner, handshake, true, 0, true);
        } else {
            awaitHandshake(server.properties(), handshake, startedAt);
        }
    }

    private void awaitHandshake(AgentProperties.McpProperties.ServerProperties server,
                                CompletableFuture<Void> handshake, long startedAt) {
        String name = server.getName();
        if (handshake == null) {
            throw new IllegalStateException("MCP server is waiting to reconnect: " + name);
        }
        try {
            handshake.get(remainingNanos(resolveMcpToolTimeout(server), startedAt), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted connecting to MCP server " + name, e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out connecting to MCP server " + name, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to connect to MCP server " + name, e.getCause());
        }
    }

    private void connectAttempt(ConfiguredServer server, ConnectionAttempt owner, CompletableFuture<Void> handshake,
                                boolean allowLazy, int nextRetry, boolean initial) {
        String name = server.properties().getName();
        try {
            synchronized (clients) {
                if (shutdownRequested.get() || connectionsInFlight.get(name) != owner
                        || handshake.isCancelled()) {
                    return;
                }
                owner.connecting = true;
            }
            if (!(allowLazy && lazyRegisterFromCache(server, owner, handshake))) {
                openConnection(server, owner, handshake);
            }
            if (handshake.isCancelled()) {
                retryConnection(name, owner, handshake, 0, true,
                    new IllegalStateException("MCP connection cancelled"));
            } else {
                handshake.complete(null);
            }
        } catch (RuntimeException e) {
            retryConnection(name, owner, handshake, nextRetry, initial, e);
        }
    }

    private void retryConnection(String name, ConnectionAttempt owner, CompletableFuture<Void> handshake,
                                 int nextRetry, boolean initial, RuntimeException failure) {
        synchronized (clients) {
            if (connectionsInFlight.get(name) == owner && owner.handshake == handshake) {
                owner.handshake = null;
                owner.connecting = false;
                int attempt = handshake.isCancelled() ? 0 : nextRetry;
                if (owner.retiringClient != null) {
                    closeRetiredClient(name, owner, attempt, initial);
                } else if (shutdownRequested.get()) {
                    connectionsInFlight.remove(name, owner);
                } else {
                    scheduleReconnect(name, attempt, initial, owner);
                }
            }
        }
        handshake.completeExceptionally(failure);
        log.warn("Failed to connect to MCP server {}: {}", name, sanitizeError(failure.getMessage()));
    }

    private void openConnection(ConfiguredServer configured, ConnectionAttempt owner,
                                CompletableFuture<Void> handshake) {
        var server = configured.properties();
        String name = server.getName();
        var store = configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
        var config = configured.schemaConfig();
        McpSyncClient client = null;
        boolean published = false;
        try {
            client = createClient(server);
            client.initialize();
            var capabilities = client.getServerCapabilities();
            boolean hasTools = capabilities == null || capabilities.tools() != null;
            List<McpSchema.Tool> tools = hasTools ? listToolsWithPagination(client) : List.of();
            var registeredTools = admittedMcpTools(server, tools);
            Instant observedAt;
            synchronized (clients) {
                if (connectionsInFlight.get(name) != owner || shutdownRequested.get() || handshake.isCancelled()) {
                    return;
                }
                var state = new McpServerState(server, client, tools, registeredTools);
                clients.put(name, state);
                try {
                    var cachedTools = lazyServers.get(name);
                    if (cachedTools != null) {
                        unregisterTools(name, cachedTools);
                    }
                    registerTools(name, server, registeredTools);
                    registerUtilityTools(name, capabilities, tools, registeredTools);
                    if (hasTools) {
                        scheduleToolRefresh(name);
                    }
                    resetMcpServerError(name);
                    lazyServers.remove(name);
                    connectionsInFlight.remove(name, owner);
                    observedAt = Instant.now();
                    published = true;
                    handshake.complete(null);
                } finally {
                    if (!published) {
                        clients.remove(name, state);
                        unregisterTools(name, tools);
                    }
                }
            }
            if (store != null && config != null) {
                try {
                    store.storeSchema(config.getId(), config.getConfigRevision(), observedAt,
                        objectMapper.writeValueAsString(tools), null, null);
                } catch (Exception e) {
                    log.debug("MCP schema cache write failed for '{}': {}", name, e.getMessage());
                }
            }
            log.info("Connected to MCP server {} ({}) with {} tools", name, server.getTransport(), tools.size());
        } catch (RuntimeException e) {
            Instant observedAt;
            synchronized (clients) {
                observedAt = connectionsInFlight.get(name) == owner && !handshake.isCancelled() ? Instant.now() : null;
            }
            if (store != null && config != null && observedAt != null) {
                try {
                    store.recordSchemaError(config.getId(), config.getConfigRevision(), observedAt,
                        sanitizeError(e.getMessage()));
                } catch (Exception ignored) {
                    // Cache bookkeeping is best-effort.
                }
            }
            throw e;
        } finally {
            if (!published && client != null) {
                synchronized (clients) {
                    owner.retiringClient = client;
                }
            }
        }
    }

    /**
     * Hermes {@code _register_from_cache_sync} parity: register tools from the
     * persisted schema cache without spawning the server process. Returns true
     * when the lazy registration succeeded (connect() must not spawn).
     */
    private boolean lazyRegisterFromCache(ConfiguredServer configured, ConnectionAttempt owner,
                                          CompletableFuture<Void> handshake) {
        var server = configured.properties();
        var configEntity = configured.schemaConfig();
        if (!properties.getMcp().isLazyStartup() || configEntity == null) {
            return false;
        }
        var store = configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
        if (store == null) {
            return false;
        }
        try {
            var cache = store.cachedSchema(configEntity.getId(), configEntity.getConfigRevision())
                .filter(store::isFresh)
                .orElse(null);
            if (cache == null || cache.getToolsJson() == null || cache.getToolsJson().isBlank()) {
                return false;
            }
            List<McpSchema.Tool> cachedTools = objectMapper.readValue(
                cache.getToolsJson(), objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, McpSchema.Tool.class));
            List<McpSchema.Tool> registeredTools = admittedMcpTools(server, cachedTools);
            if (registeredTools.isEmpty()) {
                return false;
            }
            synchronized (clients) {
                if (connectionsInFlight.get(server.getName()) != owner || shutdownRequested.get()
                        || handshake.isCancelled()) {
                    return true;
                }
                registerTools(server.getName(), server, registeredTools);
                lazyServers.put(server.getName(), registeredTools);
                connectionsInFlight.remove(server.getName(), owner);
            }
            log.info("MCP server {} (lazy): registered {} tool(s) from the schema cache (no spawn)",
                server.getName(), registeredTools.size());
            return true;
        } catch (Exception e) {
            log.debug("Lazy registration for MCP server {} unavailable: {}", server.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Hermes {@code _ensure_lazy_server_connected} parity: the first real tool
     * call on a lazily-registered server connects it now. Falls through to a
     * normal call when the server is already live.
     */
    private void ensureConnectedForCall(String serverName, long startedAt) {
        synchronized (clients) {
            if (clients.containsKey(serverName) || !lazyServers.containsKey(serverName)) {
                return;
            }
        }
        var serverProps = enabledServer(serverName);
        if (serverProps == null) {
            throw new IllegalStateException("MCP server not configured: " + serverName);
        }
        log.info("MCP server {}: first call — connecting now (lazy startup)", serverName);
        connect(serverProps, false, startedAt);
        if (!clients.containsKey(serverName)) {
            throw new IllegalStateException("MCP server failed to connect on demand: " + serverName);
        }
    }

    // h43: Follow nextCursor pagination when listing tools from an MCP server.
    // Hermes parity (tools/mcp_tool.py _drain_paginated): pass the cursor INTO the next
    // list call — calling listTools() without the cursor refetches page 1 forever.
    // Safety-capped at 100 pages to survive a misbehaving server looping its cursor.
    private List<McpSchema.Tool> listToolsWithPagination(McpSyncClient client) {
        List<McpSchema.Tool> allTools = new ArrayList<>();
        var result = client.listTools();
        int maxPages = 100;
        for (int page = 0; ; page++) {
            if (result.tools() != null) {
                allTools.addAll(result.tools());
            }
            String cursor = result.nextCursor();
            if (cursor == null || cursor.isEmpty() || page == maxPages) {
                return allTools;
            }
            var next = client.listTools(cursor);
            if (cursor.equals(next.nextCursor())) {
                log.warn("MCP tool pagination: server returned the same cursor twice — stopping");
                if (next.tools() != null) {
                    allTools.addAll(next.tools());
                }
                return allTools;
            }
            result = next;
        }
    }

    private List<McpSchema.Resource> listResourcesWithPagination(McpSyncClient client) {
        List<McpSchema.Resource> allResources = new ArrayList<>();
        try {
            var result = client.listResources();
            if (result.resources() != null) {
                allResources.addAll(result.resources());
            }
            String cursor = result.nextCursor();
            int maxPages = 100;
            while (cursor != null && !cursor.isEmpty() && maxPages-- > 0) {
                try {
                    var nextResult = client.listResources(cursor);
                    if (nextResult.resources() != null) {
                        allResources.addAll(nextResult.resources());
                    }
                    String next = nextResult.nextCursor();
                    if (cursor.equals(next)) {
                        log.warn("MCP resource pagination: server returned the same cursor twice — stopping");
                        break;
                    }
                    cursor = next;
                } catch (Exception e) {
                    log.warn("MCP resource pagination: failed to fetch next page: {}", e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("MCP resource listing failed: {}", e.getMessage());
        }
        return allResources;
    }

    private List<McpSchema.Prompt> listPromptsWithPagination(McpSyncClient client) {
        List<McpSchema.Prompt> allPrompts = new ArrayList<>();
        try {
            var result = client.listPrompts();
            if (result.prompts() != null) {
                allPrompts.addAll(result.prompts());
            }
            String cursor = result.nextCursor();
            int maxPages = 100;
            while (cursor != null && !cursor.isEmpty() && maxPages-- > 0) {
                try {
                    var nextResult = client.listPrompts(cursor);
                    if (nextResult.prompts() != null) {
                        allPrompts.addAll(nextResult.prompts());
                    }
                    String next = nextResult.nextCursor();
                    if (cursor.equals(next)) {
                        log.warn("MCP prompt pagination: server returned the same cursor twice — stopping");
                        break;
                    }
                    cursor = next;
                } catch (Exception e) {
                    log.warn("MCP prompt pagination: failed to fetch next page: {}", e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("MCP prompt listing failed: {}", e.getMessage());
        }
        return allPrompts;
    }

    McpSyncClient createClient(AgentProperties.McpProperties.ServerProperties server) {
        String transport = server.getTransport() == null ? "stdio" : server.getTransport().toLowerCase();

        return switch (transport) {
            case "stdio" -> createStdioClient(server);
            case "sse" -> createSseClient(server);
            case "http", "streamable", "streamablehttp" -> createStreamableHttpClient(server);
            default -> {
                if (!server.getBaseUrl().isBlank()) {
                    yield createStreamableHttpClient(server);
                }
                throw new IllegalArgumentException("MCP server " + server.getName() + " has no transport configured");
            }
        };
    }

    private McpSyncClient createStdioClient(AgentProperties.McpProperties.ServerProperties server) {
        String command = server.getCommand();
        String validationError = validateStdioCommand(command);
        if (validationError != null) {
            throw new IllegalArgumentException(
                "MCP server " + server.getName() + " command validation failed: " + validationError);
        }
        String inlineScriptError = validateInlineScript(command, server.getArgs());
        if (inlineScriptError != null) {
            throw new IllegalArgumentException(
                "MCP server " + server.getName() + " inline script refused: " + inlineScriptError);
        }
        // OSV malware gate (Hermes osv_check.py parity): MAL-* advisories block stdio launch.
        if (osvCheckService != null) {
            String malwareError = osvCheckService.checkPackageForMalware(command, server.getArgs());
            if (malwareError != null) {
                log.warn("MCP server {} refused by OSV malware check: {}", server.getName(), malwareError);
                throw new IllegalArgumentException(
                    "MCP server " + server.getName() + " OSV malware check: " + malwareError);
            }
        }
        List<String> effectiveCommand = stdioLaunchCommand(server, command);
        ServerParameters.Builder paramsBuilder = ServerParameters.builder(effectiveCommand.get(0))
            .args(effectiveCommand.subList(1, effectiveCommand.size()));
        // Build filtered environment for stdio subprocess
        Map<String, String> filteredEnv = buildSafeEnv(server.getEnv());
        if (!filteredEnv.isEmpty()) {
            paramsBuilder.env(filteredEnv);
        }
        ServerParameters params = paramsBuilder.build();
        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper)) {
            @Override
            protected ProcessBuilder getProcessBuilder() {
                var builder = super.getProcessBuilder();
                builder.environment().clear();
                return builder;
            }
        };
        return McpClient.sync(transport).build();
    }

    /**
     * WP-i (Hermes tools/mcp_stdio_watchdog.py parity): interpose a
     * parent-death supervisor between the agent and the stdio server command.
     * The supervisor relays stdio transparently, runs the real command in its
     * own process group, and kills that group the moment the agent process
     * dies — a hard crash can no longer orphan MCP servers.
     */
    List<String> stdioLaunchCommand(AgentProperties.McpProperties.ServerProperties server, String command) {
        List<String> argv = new java.util.ArrayList<>();
        argv.add(command);
        if (server.getArgs() != null) {
            argv.addAll(server.getArgs());
        }
        if (!server.isStdioParentDeathWatchdog()
                || !"/".equals(java.nio.file.FileSystems.getDefault().getSeparator())) {
            return argv; // watchdog disabled or non-POSIX platform
        }
        String script = mcpStdioWatchdogScript();
        if (script == null) {
            log.debug("mcp-stdio-watchdog.sh not found on classpath; spawning {} directly", command);
            return argv;
        }
        argv.add(0, "--");
        argv.add(0, String.valueOf(ProcessHandle.current().pid()));
        argv.add(0, "--ppid");
        argv.add(0, script);
        argv.add(0, "sh");
        return argv;
    }

    /** Resolve the bundled watchdog script to a real filesystem path (extract if nested in the jar). */
    String mcpStdioWatchdogScript() {
        if (watchdogScriptPath != null) {
            return watchdogScriptPath;
        }
        try (var in = getClass().getResourceAsStream("/mcp/mcp-stdio-watchdog.sh")) {
            if (in == null) {
                return null;
            }
            java.nio.file.Path target = java.nio.file.Files.createTempFile("mcp-stdio-watchdog", ".sh");
            java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true, false);
            watchdogScriptPath = target.toAbsolutePath().toString();
            return watchdogScriptPath;
        } catch (Exception e) {
            log.debug("failed to extract mcp-stdio-watchdog.sh: {}", e.getMessage());
            return null;
        }
    }

    private String watchdogScriptPath;

    private McpSyncClient createSseClient(AgentProperties.McpProperties.ServerProperties server) {
        var transport = HttpClientSseClientTransport.builder(server.getBaseUrl())
            .httpRequestCustomizer(remoteHeaderCustomizer(server))
            .build();
        return McpClient.sync(transport).build();
    }

    private McpSyncClient createStreamableHttpClient(AgentProperties.McpProperties.ServerProperties server) {
        var transport = HttpClientStreamableHttpTransport.builder(server.getBaseUrl())
            .httpRequestCustomizer(remoteHeaderCustomizer(server))
            .build();
        return McpClient.sync(transport).build();
    }

    McpSyncHttpClientRequestCustomizer remoteHeaderCustomizer(AgentProperties.McpProperties.ServerProperties server) {
        return (builder, method, uri, body, context) -> {
            Map<String, String> headers = resolveRemoteHeaders(server);
            headers.forEach(builder::setHeader);
        };
    }

    Map<String, String> resolveRemoteHeaders(AgentProperties.McpProperties.ServerProperties server) {
        Map<String, String> headers = new LinkedHashMap<>(server.getHeaders());
        if (mcpOAuthManager != null && !containsHeader(headers, "Authorization")) {
            mcpOAuthManager.getToken(server.getName())
                .filter(token -> token != null && !token.isBlank())
                .ifPresent(token -> headers.put("Authorization", "Bearer " + token));
        }
        return headers;
    }

    private boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    // ── Env var filtering for stdio subprocesses ─────────────────────────
    static Map<String, String> buildSafeEnv(Map<String, String> userEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        // Only pass through safe baseline variables from the current process
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (SAFE_ENV_KEYS.contains(key) || key.startsWith("XDG_")) {
                env.put(key, entry.getValue());
            }
        }
        // Explicitly user-specified env vars override / extend the safe set
        if (userEnv != null) {
            env.putAll(userEnv);
        }
        return env;
    }

    // ── Credential stripping in error messages ───────────────────────────
    static String sanitizeError(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CREDENTIAL_PATTERN.matcher(text).replaceAll("[REDACTED]");
    }

    private static String boundToolError(String text) {
        String message = text == null || text.isBlank() ? "MCP tool failed" : text;
        return message.length() <= MAX_TOOL_ERROR_CHARS
            ? message
            : message.substring(0, MAX_TOOL_ERROR_CHARS) + "... [truncated]";
    }

    private ToolResult mcpError(String message) {
        String error = boundToolError(sanitizeError(message));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", error);
        try {
            return new ToolResult(false, objectMapper.writeValueAsString(response), error);
        } catch (Exception e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"MCP tool failed\"}", error);
        }
    }

    // ── Stdio command validation ─────────────────────────────────────────
    private static final Set<String> SHELL_METACHARACTERS = Set.of(
        ";", "|", "&", "&&", "||", "`", "$(", "$", "(", ")", "{", "}", "<", ">",
        "\n", "\r"
    );

    /**
     * h92: Remove quoted segments from a command string, replacing them with
     * placeholders so that metacharacters inside quotes are not treated as
     * shell operators. Single-quoted and double-quoted segments are replaced
     * with a simple placeholder.
     * <p>
     * E.g. {@code grep 'a|b' file} → {@code grep _QUOTE_ file}
     * so the pipe inside the quotes is not treated as an operator.
     *
     * @param command the command string
     * @return the command with quoted segments replaced by placeholders
     */
    static String stripQuotedSegments(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        StringBuilder result = new StringBuilder();
        int len = command.length();
        int i = 0;
        while (i < len) {
            char c = command.charAt(i);
            if (c == '\'') {
                i++;
                while (i < len && command.charAt(i) != '\'') {
                    i++;
                }
                if (i < len) i++;
                result.append("_QUOTE_");
            } else if (c == '"') {
                i++;
                while (i < len && command.charAt(i) != '"') {
                    if (command.charAt(i) == '\\' && i + 1 < len) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                if (i < len) i++;
                result.append("_QUOTE_");
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    /**
     * Validates an MCP server stdio command before spawning a subprocess.
     * <p>
     * Checks:
     * <ul>
     *   <li>Command is not null or blank</li>
     *   <li>Command does not contain shell metacharacters ({@code ;}, {@code |},
     *       {@code &}, {@code &&}, {@code ||}, backticks, {@code $()}, etc.)</li>
     *   <li>If the command is a single executable path (no spaces), checks that
     *       the file exists and is executable</li>
     * </ul>
     *
     * @param command the raw command string from MCP server configuration
     * @return {@code null} if valid, or an error message describing why it is invalid
     */
    static String validateStdioCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Command is null or empty";
        }
        String trimmed = command.trim();
        // h92: Be quote-aware when checking for shell metacharacters —
        // metacharacters inside single or double quotes are literal, not operators.
        // E.g. grep 'a|b' file should NOT be flagged as containing a pipe operator.
        String unquoted = stripQuotedSegments(trimmed);
        // Check for shell metacharacters in the unquoted version
        for (String metachar : SHELL_METACHARACTERS) {
            if (unquoted.contains(metachar)) {
                return "Command contains forbidden shell metacharacter: '" + metachar + "'";
            }
        }
        // If the command is a single token (no spaces), check it exists and is executable
        if (!trimmed.contains(" ")) {
            java.nio.file.Path cmdPath = java.nio.file.Paths.get(trimmed);
            if (isAbsoluteExecutablePath(trimmed, cmdPath)) {
                java.io.File cmdFile = cmdPath.toFile();
                if (!cmdFile.exists()) {
                    return "Command executable does not exist: " + trimmed;
                }
                if (!isExecutableCommandFile(cmdFile)) {
                    return "Command file is not executable: " + trimmed;
                }
            }
            // For relative paths, rely on PATH resolution at exec time
        }
        return null;
    }

    private static boolean isAbsoluteExecutablePath(String token, java.nio.file.Path path) {
        if (path.isAbsolute()) {
            return true;
        }
        return token.startsWith("/")
            || token.startsWith("\\\\")
            || Pattern.compile("^[A-Za-z]:[\\\\/].*").matcher(token).matches();
    }

    private static boolean isExecutableCommandFile(java.io.File cmdFile) {
        if (!cmdFile.canExecute()) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return true;
        }
        String name = cmdFile.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot);
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            pathext = ".com;.exe;.bat;.cmd";
        }
        for (String allowed : pathext.toLowerCase().split(";")) {
            if (ext.equals(allowed.trim())) {
                return true;
            }
        }
        return false;
    }

    // ── Reconnection with exponential backoff ────────────────────────────
    private void scheduleReconnect(String name, int attempt, boolean initial) {
        var owner = new ConnectionAttempt();
        synchronized (clients) {
            if (shutdownRequested.get() || clients.containsKey(name)) {
                return;
            }
            var existing = connectionsInFlight.putIfAbsent(name, owner);
            if (existing != null) {
                closeRetiredClient(name, existing);
                return;
            }
            scheduleReconnect(name, attempt, initial, owner);
        }
    }

    private void scheduleReconnect(String name, int attempt, boolean initial, ConnectionAttempt owner) {
        int maxRetries = initial ? MAX_INITIAL_CONNECT_RETRIES : MAX_RECONNECT_RETRIES;
        if (attempt >= maxRetries) {
            connectionsInFlight.remove(name, owner);
            log.warn("MCP server {} failed after {} attempts, giving up", name, maxRetries);
            return;
        }
        long backoff = Math.min(1L << attempt, MAX_BACKOFF_SECONDS);
        reconnectExecutor.schedule(() -> {
            var handshake = new CompletableFuture<Void>();
            synchronized (clients) {
                if (shutdownRequested.get() || connectionsInFlight.get(name) != owner) {
                    return;
                }
                owner.handshake = handshake;
            }
            ConfiguredServer server;
            try {
                server = enabledServer(name);
            } catch (RuntimeException e) {
                retryConnection(name, owner, handshake, attempt + 1, initial, e);
                return;
            }
            if (server == null) {
                synchronized (clients) {
                    connectionsInFlight.remove(name, owner);
                }
                handshake.completeExceptionally(new IllegalStateException("MCP server is no longer enabled"));
                return;
            }
            connectAttempt(server, owner, handshake, false, attempt + 1, initial);
        }, backoff, TimeUnit.SECONDS);
    }

    private ConfiguredServer enabledServer(String name) {
        for (var server : properties.getMcp().getServers()) {
            if (name.equals(server.getName())) {
                return server.isEnabled() ? new ConfiguredServer(server, null) : null;
            }
        }
        var store = configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
        if (store == null) {
            return null;
        }
        return store.find("default", name).filter(McpServerConfigEntity::isEnabled)
            .map(config -> new ConfiguredServer(toServerProperties(config), config))
            .orElse(null);
    }

    public void reconnect(String serverName) {
        if (enabledServer(serverName) == null) {
            log.warn("Cannot reconnect unknown MCP server: {}", serverName);
            return;
        }
        var existing = clients.get(serverName);
        if (existing != null) {
            retireConnection(serverName, existing);
        } else {
            scheduleReconnect(serverName, 0, false);
        }
    }

    private void retireConnection(String name, McpServerState expected) {
        var owner = new ConnectionAttempt();
        synchronized (clients) {
            var current = clients.get(name);
            if (shutdownRequested.get() || current == null || current.client() != expected.client()) {
                return;
            }
            clients.remove(name);
            unregisterTools(name, current.tools());
            var refresh = toolRefreshFutures.remove(name);
            if (refresh != null) {
                refresh.cancel(false);
            }
            owner.retiringClient = current.client();
            connectionsInFlight.put(name, owner);
            closeRetiredClient(name, owner);
        }
    }

    /** Called under the clients monitor; the SDK wait runs outside it. */
    private void closeRetiredClient(String name, ConnectionAttempt owner) {
        closeRetiredClient(name, owner, 0, false);
    }

    private void closeRetiredClient(String name, ConnectionAttempt owner, int attempt, boolean initial) {
        var retired = owner.retiringClient;
        if (retired == null || owner.closing || owner.connecting || toolCallExecutor.isShutdown()) {
            return;
        }
        owner.closing = true;
        toolCallExecutor.execute(() -> {
            boolean closed = false;
            try {
                closed = retired.closeGracefully();
            } catch (RuntimeException failure) {
                log.warn("MCP client {} close failed: {}", name, sanitizeError(failure.getMessage()));
            } finally {
                synchronized (clients) {
                    owner.closing = false;
                    if (closed) {
                        owner.retiringClient = null;
                        if (shutdownRequested.get()) {
                            connectionsInFlight.remove(name, owner);
                        } else if (connectionsInFlight.get(name) == owner) {
                            scheduleReconnect(name, attempt, initial, owner);
                        }
                    } else {
                        log.warn("MCP client {} is still retiring; reconnect suspended until close is confirmed", name);
                    }
                }
            }
        });
    }

    // ── Dynamic tool refresh ─────────────────────────────────────────────
    private final Map<String, ScheduledFuture<?>> toolRefreshFutures = new ConcurrentHashMap<>();

    private void scheduleToolRefresh(String serverName) {
        // Cancel any existing tool refresh task before scheduling a new one
        ScheduledFuture<?> oldFuture = toolRefreshFutures.remove(serverName);
        if (oldFuture != null) {
            oldFuture.cancel(false);
            log.debug("Cancelled previous tool refresh for MCP server {}", serverName);
        }
        McpSyncClient expected = clients.get(serverName).client();
        ScheduledFuture<?> future = toolRefreshExecutor.scheduleWithFixedDelay(() -> {
            try {
                var current = clients.get(serverName);
                if (current != null && current.client() == expected) {
                    refreshTools(serverName, current);
                }
            } catch (Exception e) {
                log.warn("Tool refresh for MCP server {} failed: {}", serverName, e.getMessage());
            }
        }, TOOL_REFRESH_INTERVAL_SECONDS, TOOL_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        toolRefreshFutures.put(serverName, future);
    }

    public void refreshTools(String serverName) {
        refreshTools(serverName, clients.get(serverName));
    }

    private void refreshTools(String serverName, McpServerState state) {
        if (state == null) {
            return;
        }
        try {
            var freshTools = listToolsWithPagination(state.client());
            var capabilities = safeServerCapabilities(serverName, state.client());
            synchronized (clients) {
                if (shutdownRequested.get() || clients.get(serverName) != state) {
                    return;
                }
                var oldTools = state.admittedTools();
                var admitted = admittedMcpTools(state.properties(), freshTools);
                if (state.registrationComplete() && state.tools().equals(freshTools) && oldTools.equals(admitted)) {
                    return;
                }
                // Invalidate in-flight admissions before touching the registry. A failed
                // update stays unpublished and the next refresh reconciles it again.
                clients.put(serverName, new McpServerState(state.properties(), state.client(), freshTools, admitted, false));
                Set<String> newNames = admitted.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
                for (var previous : oldTools) {
                    if (!newNames.contains(previous.name())) {
                        String fullName = mcpPrefixedToolName(serverName, previous.name());
                        toolRegistry().deregisterDynamic(fullName, mcpToolsetName(serverName));
                        if (fingerprintStore != null) fingerprintStore.remove(fullName);
                    }
                }
                registerTools(serverName, state.properties(), admitted);
                registerUtilityTools(serverName, capabilities, freshTools, admitted);
                clients.put(serverName, new McpServerState(state.properties(), state.client(), freshTools, admitted));
            }
        } catch (Exception e) {
            log.warn("Failed to refresh tools from MCP server {}: {}", serverName, e.getMessage());
            // Finding 8.2: A failed tool refresh may indicate the server is unhealthy.
            // Trigger a reconnect if the error looks like a connection failure.
            if (isConnectionError(e)) {
                log.info("MCP server {} tool refresh failed with connection error, triggering reconnect", serverName);
                retireConnection(serverName, state);
            }
        }
    }

    private void registerTools(String serverName, AgentProperties.McpProperties.ServerProperties server,
                               List<McpSchema.Tool> registeredTools) {
        recordMcpToolTrustMetadata(serverName, server, registeredTools);
        for (McpSchema.Tool tool : registeredTools) {
            String fullName = mcpPrefixedToolName(serverName, tool.name());
            Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
            // ── Rug pull detection: check fingerprint ──
            if (fingerprintStore != null) {
                fingerprintStore.recordFingerprint(fullName, tool.description(), schema);
            }
            // h46: When two MCP servers provide a tool with the same name,
            // prefer the server-native tool over any generated utility.
            // Log a warning about the collision.
            if (toolRegistry().getDefinitions().stream()
                    .anyMatch(td -> td.name().equals(fullName)
                        || td.name().endsWith("__" + sanitizeMcpNameComponent(tool.name())))) {
                log.warn("MCP tool name collision: '{}' from server '{}' collides with an existing tool. " +
                    "Preferring server-native tool.", tool.name(), serverName);
            }
            ToolDefinition definition = convertToolDefinition(fullName, tool);
            toolRegistry().registerDynamic(fullName, mcpToolsetName(serverName),
                definition, new McpToolHandler(serverName, tool.name()));
        }
    }

    private Set<String> nativeNames(String serverName, List<McpSchema.Tool> tools) {
        return tools.stream()
            .filter(tool -> tool != null && tool.name() != null && !tool.name().isBlank())
            .map(tool -> mcpPrefixedToolName(serverName, tool.name()))
            .collect(Collectors.toSet());
    }

    private void unregisterTools(String serverName, List<McpSchema.Tool> tools) {
        if (mcpToolTrustService != null) {
            mcpToolTrustService.recordServerTools(serverName, null, List.of());
        }
        ToolRegistry registry = applicationContext != null ? toolRegistry() : null;
        if (registry == null) {
            return;
        }
        String toolset = mcpToolsetName(serverName);
        tools.stream().filter(java.util.Objects::nonNull)
            .forEach(tool -> registry.deregisterDynamic(mcpPrefixedToolName(serverName, tool.name()), toolset));
        for (String utility : UTILITY_TOOL_NAMES) {
            registry.deregisterDynamic(mcpPrefixedToolName(serverName, utility), toolset);
        }
    }

    private List<McpSchema.Tool> admittedMcpTools(AgentProperties.McpProperties.ServerProperties server,
                                                  List<McpSchema.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
            .filter(tool -> tool != null && shouldRegisterMcpTool(server, tool.name()))
            .filter(tool -> passesDefinitionScan(server.getName(), tool))
            .toList();
    }

    private boolean passesDefinitionScan(String serverName, McpSchema.Tool tool) {
        if (definitionScanner == null) return true;
        String fullName = mcpPrefixedToolName(serverName, tool.name());
        var result = definitionScanner.scan(fullName, tool.description(),
            tool.inputSchema() != null ? tool.inputSchema() : Map.of());
        if (!result.isClean()) {
            log.warn("MCP tool '{}' definition scan (blocked={}): {}",
                fullName, result.isBlocked(), result.getThreatDescription());
        }
        return !result.isBlocked();
    }

    private boolean shouldRegisterMcpTool(AgentProperties.McpProperties.ServerProperties server, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        AgentProperties.McpProperties.Tools toolsConfig = server == null ? null : server.getTools();
        List<String> include = toolsConfig == null ? null : toolsConfig.getInclude();
        if (include != null) {
            return matchesNameFilter(toolName, include);
        }
        List<String> exclude = toolsConfig == null ? null : toolsConfig.getExclude();
        return exclude == null || !matchesNameFilter(toolName, exclude);
    }

    static boolean matchesNameFilter(String toolName, List<String> patterns) {
        if (toolName == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String rawPattern : patterns) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            String pattern = rawPattern.strip();
            if (pattern.equals(toolName) || globMatches(toolName, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String value, String glob) {
        StringBuilder regex = new StringBuilder("^");
        boolean hasGlob = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
                hasGlob = true;
            } else if (c == '?') {
                regex.append('.');
                hasGlob = true;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return hasGlob && Pattern.compile(regex.toString()).matcher(value).matches();
    }

    private static final List<String> UTILITY_TOOL_NAMES =
        List.of("list_resources", "read_resource", "list_prompts", "get_prompt");

    private void registerUtilityTools(String serverName, McpSchema.ServerCapabilities capabilities,
                                      List<McpSchema.Tool> discovered, List<McpSchema.Tool> admitted) {
        var nativeFullNames = nativeNames(serverName, discovered);
        var admittedFullNames = nativeNames(serverName, admitted);
        for (String utility : UTILITY_TOOL_NAMES) {
            String fullName = mcpPrefixedToolName(serverName, utility);
            if (nativeFullNames.contains(fullName) && !admittedFullNames.contains(fullName)) {
                // The native tool reserves this name even when it is excluded.
                toolRegistry().deregisterDynamic(fullName, mcpToolsetName(serverName));
            }
        }
        if (capabilities == null) {
            return;
        }
        if (capabilities.resources() != null) {
            registerUtilityTool(serverName, nativeFullNames, "list_resources",
                "List available resources from MCP server '" + serverName + "'",
                Map.of(), List.of(), new McpUtilityToolHandler(serverName, McpUtilityAction.LIST_RESOURCES));
            registerUtilityTool(serverName, nativeFullNames, "read_resource",
                "Read a resource by URI from MCP server '" + serverName + "'",
                Map.of("uri", Map.of("type", "string", "description", "URI of the resource to read")),
                List.of("uri"), new McpUtilityToolHandler(serverName, McpUtilityAction.READ_RESOURCE));
        }
        if (capabilities.prompts() != null) {
            registerUtilityTool(serverName, nativeFullNames, "list_prompts",
                "List available prompts from MCP server '" + serverName + "'",
                Map.of(), List.of(), new McpUtilityToolHandler(serverName, McpUtilityAction.LIST_PROMPTS));
            Map<String, Object> argumentsProperty = new LinkedHashMap<>();
            argumentsProperty.put("type", "object");
            argumentsProperty.put("description", "Optional arguments to pass to the prompt");
            argumentsProperty.put("properties", Map.of());
            argumentsProperty.put("additionalProperties", true);
            registerUtilityTool(serverName, nativeFullNames, "get_prompt",
                "Get a prompt by name from MCP server '" + serverName + "'",
                Map.of(
                    "name", Map.of("type", "string", "description", "Name of the prompt to retrieve"),
                    "arguments", argumentsProperty
                ),
                List.of("name"), new McpUtilityToolHandler(serverName, McpUtilityAction.GET_PROMPT));
        }
    }

    private McpSchema.ServerCapabilities safeServerCapabilities(String serverName, McpSyncClient client) {
        try {
            return client.getServerCapabilities();
        } catch (Exception e) {
            log.debug("Could not read MCP server {} capabilities for utility tools: {}", serverName, e.getMessage());
            return null;
        }
    }

    private void registerUtilityTool(String serverName,
                                     Set<String> nativeFullNames,
                                     String utilityName,
                                     String description,
                                     Map<String, Object> properties,
                                     List<String> required,
                                     ToolHandler handler) {
        String fullName = mcpPrefixedToolName(serverName, utilityName);
        if (nativeFullNames.contains(fullName)) {
            log.info("MCP server '{}' native tool '{}' shadows generated utility '{}'; keeping native tool",
                serverName, utilityName, fullName);
            return;
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        toolRegistry().registerDynamic(fullName, mcpToolsetName(serverName),
            new ToolDefinition(fullName, description, parameters), handler);
    }

    private void recordMcpToolTrustMetadata(String serverName,
                                            AgentProperties.McpProperties.ServerProperties server,
                                            List<McpSchema.Tool> tools) {
        if (mcpToolTrustService == null || serverName == null || tools == null) {
            return;
        }
        mcpToolTrustService.recordServerTools(serverName, server == null ? null : server.getTrust(), tools);
    }

    public List<McpServerInfo> listServers() {
        return clients.values().stream()
            .map(s -> new McpServerInfo(
                s.properties().getName(),
                s.properties().getBaseUrl(),
                s.properties().getTransport(),
                s.tools().size(),
                s.tools().stream().map(McpSchema.Tool::name).toList()
            ))
            .toList();
    }

    public List<DiscoveredTool> listDiscoveredTools() {
        List<DiscoveredTool> result = new ArrayList<>();
        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            for (McpSchema.Tool tool : entry.getValue().tools()) {
                result.add(new DiscoveredTool(serverName, tool.name(),
                    convertToolDefinition(mcpPrefixedToolName(serverName, tool.name()), tool)));
            }
        }
        return result;
    }

    public String readResource(String serverName, String uri) {
        return readResource(serverName, uri, null);
    }

    private String readResource(String serverName, String uri, MediaPublisher publisher) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            var result = callMcpWithTimeout(state, "read_resource '" + uri + "'",
                () -> state.client().readResource(new McpSchema.ReadResourceRequest(uri)));
            return safeList(result.contents()).stream()
                .map(contents -> resourceContentsText(contents, publisher))
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("MCP read resource failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    private MediaPublisher publisherFor(Session session) {
        if (session == null || !"telegram".equalsIgnoreCase(session.getMetadata("platform"))) {
            return null;
        }
        return (kind, mime, name, bytes) -> artifacts.publishGenerated(session, kind, mime, name, bytes);
    }

    String listResourcesForTool(String serverName) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            List<Map<String, Object>> resources = callMcpWithTimeout(state, "list_resources",
                () -> listResourcesWithPagination(state.client())).stream()
                .map(McpLifecycleManager::resourceEntry)
                .toList();
            return writeJson(Map.of("resources", resources));
        } catch (Exception e) {
            throw new RuntimeException("MCP list resources failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    private String readResourceForTool(String serverName, String arguments, Session session) {
        Map<String, Object> args = parseObjectArguments(arguments);
        Object uri = args.get("uri");
        if (uri == null || uri.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required parameter 'uri'");
        }
        return writeJson(Map.of("result", readResource(serverName, uri.toString(), publisherFor(session))));
    }

    String listPromptsForTool(String serverName) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            List<Map<String, Object>> prompts = callMcpWithTimeout(state, "list_prompts",
                () -> listPromptsWithPagination(state.client())).stream()
                .map(McpLifecycleManager::promptEntry)
                .toList();
            return writeJson(Map.of("prompts", prompts));
        } catch (Exception e) {
            throw new RuntimeException("MCP list prompts failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    String getPromptForTool(String serverName, String arguments, Session session) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        Map<String, Object> args = parseObjectArguments(arguments);
        Object name = args.get("name");
        if (name == null || name.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required parameter 'name'");
        }
        Map<String, Object> promptArgs = objectMap(args.get("arguments"));
        try {
            var result = callMcpWithTimeout(state, "get_prompt '" + name + "'",
                () -> state.client().getPrompt(new McpSchema.GetPromptRequest(name.toString(), promptArgs)));
            Map<String, Object> response = new LinkedHashMap<>();
            if (result.description() != null && !result.description().isBlank()) {
                response.put("description", result.description());
            }
            MediaPublisher publisher = publisherFor(session);
            response.put("messages", safeList(result.messages()).stream()
                .map(message -> promptMessageEntry(message, publisher))
                .toList());
            return writeJson(response);
        } catch (Exception e) {
            throw new RuntimeException("MCP get prompt failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    public String formatCallToolResult(McpSchema.CallToolResult result, String serverName) {
        return formatCallToolResult(result, serverName, null);
    }

    String formatCallToolResult(McpSchema.CallToolResult result, String serverName, MediaPublisher publisher) {
        String text = renderContentBlocks(result == null ? null : result.content(), serverName, publisher);
        text = truncateMcpTextResult(text);
        Object structuredContent = result == null ? null : result.structuredContent();
        structuredContent = capStructuredContent(structuredContent);
        Map<String, Object> meta = stripReservedMetaKeys(result == null ? null : result.meta());

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!text.isBlank()) {
            payload.put("result", text);
        }
        if (structuredContent != null) {
            if (text.isBlank()) {
                payload.put("result", structuredContent);
            } else {
                payload.put("structuredContent", structuredContent);
            }
        }
        if (meta != null) {
            payload.put("_meta", meta);
        }
        payload.putIfAbsent("result", text);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return writeJson(Map.of("result", text));
        }
    }

    private Object capStructuredContent(Object structuredContent) {
        if (structuredContent == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(structuredContent);
            if (json.length() > MCP_HARD_RESULT_CAP_CHARS) {
                return truncateMcpTextResult(json);
            }
        } catch (Exception ignored) {
            // Keep non-serializable structuredContent on the existing best-effort path.
        }
        return structuredContent;
    }

    static String truncateMcpTextResult(String text) {
        return truncateMcpTextResult(text, MCP_HARD_RESULT_CAP_CHARS);
    }

    static String truncateMcpTextResult(String text, int maxChars) {
        String value = text == null ? "" : text;
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        int headChars = (int) (maxChars * 0.4);
        int tailChars = maxChars - headChars;
        int omitted = value.length() - headChars - tailChars;
        return value.substring(0, headChars)
            + "\n\n... [MCP RESULT TRUNCATED - " + formatCount(omitted)
            + " chars omitted out of " + formatCount(value.length()) + " total] ...\n\n"
            + value.substring(value.length() - tailChars);
    }

    private static String formatCount(int value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    String mcpCircuitBreakerBlockReason(String serverName) {
        int count = mcpServerErrorCounts.getOrDefault(serverName, 0);
        if (count < MCP_CIRCUIT_BREAKER_THRESHOLD) {
            return null;
        }
        Long openedAt = mcpServerBreakerOpenedAtNanos.get(serverName);
        if (openedAt == null) {
            return null;
        }
        long ageNanos = System.nanoTime() - openedAt;
        long cooldownNanos = MCP_CIRCUIT_BREAKER_COOLDOWN.toNanos();
        if (ageNanos >= cooldownNanos) {
            return null;
        }
        long remainingSeconds = Math.max(1, Duration.ofNanos(cooldownNanos - ageNanos).toSeconds());
        return "MCP server '" + serverName + "' is unreachable after " + count
            + " consecutive failures. Auto-retry available in ~" + remainingSeconds
            + "s. Do not retry this tool yet; use alternative approaches or ask the user to check the MCP server.";
    }

    void bumpMcpServerError(String serverName) {
        int count = mcpServerErrorCounts.merge(serverName, 1, Integer::sum);
        if (count >= MCP_CIRCUIT_BREAKER_THRESHOLD) {
            mcpServerBreakerOpenedAtNanos.put(serverName, System.nanoTime());
        }
    }

    void resetMcpServerError(String serverName) {
        if (serverName == null) {
            return;
        }
        mcpServerErrorCounts.put(serverName, 0);
        mcpServerBreakerOpenedAtNanos.remove(serverName);
    }

    int mcpServerErrorCount(String serverName) {
        return mcpServerErrorCounts.getOrDefault(serverName, 0);
    }

    void forceMcpCircuitBreakerForTest(String serverName, int count, Duration openedAgo) {
        mcpServerErrorCounts.put(serverName, count);
        long age = openedAgo == null ? 0L : openedAgo.toNanos();
        mcpServerBreakerOpenedAtNanos.put(serverName, System.nanoTime() - age);
    }

    Duration resolveMcpToolTimeout(AgentProperties.McpProperties.ServerProperties server) {
        double seconds = server == null ? 0 : server.getTimeout();
        if (seconds <= 0 && server != null) {
            seconds = server.getTimeoutSeconds();
        }
        if (seconds <= 0 && properties != null && properties.getTimeouts() != null
            && properties.getTimeouts().getMcp() != null) {
            seconds = properties.getTimeouts().getMcp().getToolCall();
        }
        if (seconds <= 0) {
            seconds = DEFAULT_MCP_TOOL_TIMEOUT_SECONDS;
        }
        long millis = Math.max(1L, Math.round(seconds * 1000.0));
        return Duration.ofMillis(millis);
    }

    public McpSchema.CallToolResult executeTool(String serverName, String toolName, String argumentsJson) {
        return executeTool(serverName, toolName, argumentsJson, false, null);
    }

    public McpSchema.CallToolResult executeModelTool(String serverName, String toolName, String argumentsJson,
                                                    ToolTrustMetadata admission) {
        return executeTool(serverName, toolName, argumentsJson, true, admission);
    }

    private McpSchema.CallToolResult executeTool(String serverName, String toolName, String argumentsJson,
                                                boolean modelCall, ToolTrustMetadata admission) {
        // Hermes _ensure_lazy_server_connected parity: a lazily-registered
        // server connects on the first real call.
        long startedAt = System.nanoTime();
        ensureConnectedForCall(serverName, startedAt);
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            try {
                return callToolWithTimeout(state, serverName, toolName, args, startedAt, modelCall, admission);
            } catch (Exception callError) {
                if (!isAuthError(callError)) {
                    throw callError;
                }
                recoverOAuthOrThrow(serverName, callError);
                try {
                    var retryState = clients.getOrDefault(serverName, state);
                    return callToolWithTimeout(retryState, serverName, toolName, args, startedAt, modelCall, admission);
                } catch (Exception retryError) {
                    if (isAuthError(retryError)) {
                        throw reauthRequired(serverName, retryError);
                    }
                    throw new RuntimeException("MCP tool call failed after OAuth recovery: "
                        + sanitizeError(retryError.getMessage()), retryError);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("MCP tool call failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    private McpSchema.CallToolResult callToolWithTimeout(McpServerState state, String serverName, String toolName,
                                                         Map<String, Object> args, long startedAt,
                                                         boolean modelCall, ToolTrustMetadata admission) throws Exception {
        if (!state.registrationComplete()) {
            throw new IllegalStateException("MCP tool registration is incomplete: " + serverName);
        }
        var definition = state.admittedTools().stream().filter(tool -> toolName.equals(tool.name())).findFirst()
            .orElseThrow(() -> new IllegalStateException("MCP tool is not admitted: " + toolName));
        if (modelCall) {
            var current = ToolTrustMetadata.nativeTool(serverName, state.properties().getTrust(), definition);
            if (admission != null && !admission.equals(current)) {
                throw new IllegalStateException("MCP tool changed after admission: " + toolName);
            }
            if (admission == null && properties.getSecurity().isApprovalsEnabled() && current.requiresApproval()) {
                throw new IllegalStateException("MCP tool requires approval: " + toolName);
            }
        }
        try {
            return callMcpWithTimeout(state, "tool '" + toolName + "'",
                () -> {
                    if (modelCall && clients.get(serverName) != state) {
                        throw new IllegalStateException("MCP tool changed before dispatch: " + toolName);
                    }
                    return state.client().callTool(new McpSchema.CallToolRequest(toolName, args));
                }, startedAt);
        } catch (Exception e) {
            if (!isAuthError(e) && isConnectionError(e)) {
                retireConnection(serverName, state);
            }
            throw e;
        }
    }

    private static boolean isConnectionError(Throwable error) {
        if (isJsonRpcError(error)) {
            return false;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("connection") || lower.contains("closed")
            || lower.contains("disconnected") || lower.contains("refused")
            || lower.contains("reset") || lower.contains("timeout") || lower.contains("timed out");
    }

    private <T> T callMcpWithTimeout(McpServerState state, String operation, Callable<T> action) throws Exception {
        return callMcpWithTimeout(state, operation, action, System.nanoTime());
    }

    private <T> T callMcpWithTimeout(McpServerState state, String operation, Callable<T> action, long startedAt)
            throws Exception {
        Duration timeout = resolveMcpToolTimeout(state == null ? null : state.properties());
        long remaining = remainingNanos(timeout, startedAt);
        if (remaining <= 0) {
            throw new TimeoutException("MCP deadline expired before " + operation);
        }
        Future<T> future = toolCallExecutor.submit(action);
        try {
            return future.get(remainingNanos(timeout, startedAt), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // WP-i (Hermes in-flight RPC teardown parity): a timed-out stdio call
            // typically means the underlying process/pipe is wedged. Leaving the
            // client connected turns EVERY later call into another full timeout.
            // Tear the wedged client down so the reconnect path can rebuild it.
            teardownWedgedClient(state, operation, timeout);
            throw new RuntimeException("MCP call timed out after configured timeout: "
                + formatSeconds(timeout) + " for " + operation, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static long remainingNanos(Duration timeout, long startedAt) {
        return timeout.toNanos() - (System.nanoTime() - startedAt);
    }

    /**
     * WP-i: close a client whose RPC timed out and schedule an asynchronous
     * reconnect. The in-flight call already failed to its caller; this exists
     * so the NEXT call finds a healthy client instead of inheriting the wedge.
     */
    private void teardownWedgedClient(McpServerState state, String operation, Duration timeout) {
        if (state == null || state.client() == null) {
            return;
        }
        String serverName = serverNameOf(state);
        if (serverName == null) {
            return;
        }
        retireConnection(serverName, state);
        log.info("MCP {} wedged ({} timeout {}); retiring client before reconnect",
            serverName, operation, formatSeconds(timeout));
    }

    private String serverNameOf(McpServerState state) {
        return state.properties() == null ? null : state.properties().getName();
    }

    private static String formatSeconds(Duration timeout) {
        double seconds = timeout.toMillis() / 1000.0;
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    private void recoverOAuthOrThrow(String serverName, Exception cause) {
        if (mcpOAuthManager == null) {
            throw reauthRequired(serverName, cause);
        }
        try {
            mcpOAuthManager.refreshToken(serverName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw reauthRequired(serverName, e);
        } catch (Exception e) {
            log.warn("MCP OAuth recovery for server {} failed: {}", serverName, sanitizeError(e.getMessage()));
            throw reauthRequired(serverName, e);
        }
    }

    private RuntimeException reauthRequired(String serverName, Exception cause) {
        return new RuntimeException("MCP server '" + serverName
            + "' requires re-authentication (needs_reauth=true). Re-authenticate this MCP server before retrying. Cause: "
            + sanitizeError(cause.getMessage()), cause);
    }

    private static boolean isJsonRpcError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpError mcpError && mcpError.getJsonRpcError() != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthError(Throwable error) {
        if (isJsonRpcError(error)) {
            return false;
        }
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("401")
                    || lower.contains("403")
                    || lower.contains("unauthorized")
                    || lower.contains("unauthorised")
                    || lower.contains("forbidden")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public synchronized void reloadConfiguredServers() {
        closeAll();
        connectConfiguredServers();
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void closeAll() {
        var closingReconnect = reconnectExecutor;
        var closingRefresh = toolRefreshExecutor;
        var closingCalls = toolCallExecutor;
        synchronized (clients) {
            shutdownRequested.set(true);
            connectionsInFlight.values().forEach(owner -> {
                if (owner.handshake != null) {
                    owner.handshake.cancel(false);
                }
            });
            // Keep custody of retired clients whose transport has not confirmed closure.
            connectionsInFlight.values().removeIf(owner -> !owner.connecting && owner.retiringClient == null);
            clients.forEach((name, state) -> {
                unregisterTools(name, state.tools());
                var owner = new ConnectionAttempt();
                owner.retiringClient = state.client();
                connectionsInFlight.put(name, owner);
            });
            clients.clear();
            lazyServers.forEach(this::unregisterTools);
            lazyServers.clear();
            toolRefreshFutures.values().forEach(future -> future.cancel(false));
            toolRefreshFutures.clear();
            closingCalls.shutdownNow();
        }
        closingReconnect.shutdownNow();
        closingRefresh.shutdownNow();
        awaitTerminationBounded(closingReconnect, "reconnectExecutor");
        awaitTerminationBounded(closingRefresh, "toolRefreshExecutor");
        awaitTerminationBounded(closingCalls, "toolCallExecutor");
        if (fingerprintStore != null) fingerprintStore.clear();
        if (rateLimiter != null) rateLimiter.clear();
        mcpServerErrorCounts.clear();
        mcpServerBreakerOpenedAtNanos.clear();
        synchronized (clients) {
            reconnectExecutor = newReconnectExecutor();
            toolRefreshExecutor = newToolRefreshExecutor();
            toolCallExecutor = newToolCallExecutor();
            connectionsInFlight.forEach(this::closeRetiredClient);
        }
    }

    /** M29: bounded awaitTermination so closeAll never hangs on a stuck task. */
    private static void awaitTerminationBounded(ExecutorService executor, String name) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.debug("MCP {} did not terminate in 5s during closeAll", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for MCP {} termination", name);
        }
    }

    static ToolDefinition convertToolDefinition(String fullName, McpSchema.Tool tool) {
        Map<String, Object> parameters = normalizeMcpInputSchema(tool.inputSchema());
        parameters.putIfAbsent("required", List.of());
        return new ToolDefinition(fullName, mcpToolDescription(fullName, tool), parameters);
    }

    static Map<String, Object> normalizeMcpInputSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return new LinkedHashMap<>(Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            ));
        }
        Object normalized = rewriteLocalSchemaRefs(schema);
        normalized = stripNullableUnions(normalized);
        normalized = collapseConstUnions(normalized);
        normalized = repairObjectShape(normalized);
        if (!(normalized instanceof Map<?, ?> normalizedMap)) {
            return new LinkedHashMap<>(Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            ));
        }
        Map<String, Object> result = objectMap(normalizedMap);
        result.put("type", "object");
        if (!(result.get("properties") instanceof Map<?, ?>)) {
            result.put("properties", Map.of());
        }
        result.putIfAbsent("required", List.of());
        return result;
    }

    private static Object rewriteLocalSchemaRefs(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(McpLifecycleManager::rewriteLocalSchemaRefs).toList();
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().toString();
            Object value = entry.getValue();
            if (("properties".equals(key) || "patternProperties".equals(key)) && value instanceof Map<?, ?> props) {
                Map<String, Object> propertySchemas = new LinkedHashMap<>();
                for (Map.Entry<?, ?> prop : props.entrySet()) {
                    if (prop.getKey() != null) {
                        propertySchemas.put(prop.getKey().toString(), rewriteLocalSchemaRefs(prop.getValue()));
                    }
                }
                normalized.put(key, propertySchemas);
            } else {
                String outputKey = "definitions".equals(key) ? "$defs" : key;
                normalized.put(outputKey, rewriteLocalSchemaRefs(value));
            }
        }
        Object ref = normalized.get("$ref");
        if (ref instanceof String refText && refText.startsWith("#/definitions/")) {
            normalized.put("$ref", "#/$defs/" + refText.substring("#/definitions/".length()));
        }
        return normalized;
    }

    private static Object stripNullableUnions(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(McpLifecycleManager::stripNullableUnions).toList();
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(entry.getKey().toString(), stripNullableUnions(entry.getValue()));
            }
        }
        Object union = normalized.containsKey("anyOf") ? normalized.get("anyOf") : normalized.get("oneOf");
        String unionKey = normalized.containsKey("anyOf") ? "anyOf" : "oneOf";
        if (union instanceof List<?> variants) {
            List<Object> nonNull = variants.stream()
                .map(Object.class::cast)
                .filter(variant -> !isNullSchema(variant))
                .toList();
            if (nonNull.size() == 1 && nonNull.size() < variants.size()) {
                Map<String, Object> collapsed = nonNull.getFirst() instanceof Map<?, ?> branch
                    ? objectMap(branch)
                    : new LinkedHashMap<>();
                normalized.forEach((key, value) -> {
                    if (!"anyOf".equals(key) && !"oneOf".equals(key) && !collapsed.containsKey(key)) {
                        collapsed.put(key, value);
                    }
                });
                collapsed.put("nullable", true);
                return collapsed;
            }
            if (nonNull.size() < variants.size()) {
                normalized.put(unionKey, nonNull);
                normalized.put("nullable", true);
            }
        }
        Object type = normalized.get("type");
        if (type instanceof List<?> types && types.stream().anyMatch("null"::equals)) {
            List<String> nonNullTypes = types.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(item -> !"null".equals(item))
                .distinct()
                .toList();
            if (nonNullTypes.size() == 1) {
                normalized.put("type", nonNullTypes.getFirst());
                normalized.put("nullable", true);
            } else if (!nonNullTypes.isEmpty()) {
                normalized.put("type", nonNullTypes);
                normalized.put("nullable", true);
            }
        }
        return normalized;
    }

    private static Object collapseConstUnions(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(McpLifecycleManager::collapseConstUnions).toList();
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(entry.getKey().toString(), collapseConstUnions(entry.getValue()));
            }
        }
        Object union = normalized.containsKey("anyOf") ? normalized.get("anyOf") : normalized.get("oneOf");
        if (union instanceof List<?> variants && !variants.isEmpty()) {
            List<Object> constValues = new ArrayList<>();
            boolean allConst = true;
            for (Object variant : variants) {
                if (variant instanceof Map<?, ?> branch && branch.containsKey("const")) {
                    constValues.add(branch.get("const"));
                } else {
                    allConst = false;
                    break;
                }
            }
            if (allConst && sameJsonType(constValues)) {
                normalized.remove("anyOf");
                normalized.remove("oneOf");
                normalized.put("enum", constValues.stream().distinct().toList());
                normalized.put("type", jsonTypeName(constValues.getFirst()));
            }
        }
        return normalized;
    }

    private static Object repairObjectShape(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().map(McpLifecycleManager::repairObjectShape).toList();
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        Map<String, Object> repaired = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                repaired.put(entry.getKey().toString(), repairObjectShape(entry.getValue()));
            }
        }
        if (!repaired.containsKey("type")
            && (repaired.containsKey("properties") || repaired.containsKey("required"))) {
            repaired.put("type", "object");
        }
        if ("object".equals(repaired.get("type"))) {
            Object properties = repaired.get("properties");
            Map<?, ?> props;
            if (properties instanceof Map<?, ?> propertyMap) {
                props = propertyMap;
            } else {
                repaired.put("properties", Map.of());
                props = Map.of();
            }
            Object required = repaired.get("required");
            if (required instanceof List<?> rawRequired) {
                Set<String> propertyNames = props.keySet().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toSet());
                List<String> valid = rawRequired.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(propertyNames::contains)
                    .distinct()
                    .toList();
                if (valid.isEmpty()) {
                    repaired.remove("required");
                } else {
                    repaired.put("required", valid);
                }
            }
        }
        return repaired;
    }

    private static boolean isNullSchema(Object value) {
        return value instanceof Map<?, ?> map && "null".equals(map.get("type"));
    }

    private static boolean sameJsonType(List<Object> values) {
        if (values.isEmpty()) {
            return false;
        }
        String type = jsonTypeName(values.getFirst());
        if (type == null) {
            return false;
        }
        return values.stream().allMatch(value -> type.equals(jsonTypeName(value)));
    }

    private static String jsonTypeName(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        return null;
    }

    static String sanitizeMcpNameComponent(String value) {
        return MCP_NAME_COMPONENT_UNSAFE.matcher(String.valueOf(value == null ? "" : value)).replaceAll("_");
    }

    public static String mcpPrefixedToolName(String serverName, String toolName) {
        String fullName = MCP_TOOL_NAME_PREFIX + sanitizeMcpNameComponent(serverName)
            + MCP_NAME_DELIMITER + sanitizeMcpNameComponent(toolName);
        return clampMcpToolName(fullName);
    }

    private static final int MCP_TOOL_NAME_MAX_LENGTH = 64;
    private static final int MCP_TOOL_NAME_HASH_LENGTH = 8;
    private static final java.util.Set<String> CLAMPED_NAMES_WARNED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * OpenAI-compatible providers reject function names longer than 64 chars,
     * which kills the WHOLE request when one generated name is longer.
     * Hermes parity (tools/mcp_tool_schema.py, #81331): clamp with a
     * deterministic sha256 hash suffix — distinct long names never collide,
     * the same inputs always produce the same shortened name. Dispatch is
     * unaffected: handlers close over the original unprefixed tool name.
     */
    static String clampMcpToolName(String fullName) {
        if (fullName.length() <= MCP_TOOL_NAME_MAX_LENGTH) {
            return fullName;
        }
        String suffix = "_" + sha256Prefix(fullName, MCP_TOOL_NAME_HASH_LENGTH);
        if (CLAMPED_NAMES_WARNED.add(fullName)) { // recomputed on health refresh — warn once
            log.warn("MCP tool name '{}' ({} chars) exceeds the {}-char provider limit; "
                + "shortened to a deterministic hash-suffixed name",
                fullName, fullName.length(), MCP_TOOL_NAME_MAX_LENGTH);
        }
        return fullName.substring(0, MCP_TOOL_NAME_MAX_LENGTH - suffix.length()) + suffix;
    }

    private static String sha256Prefix(String value, int chars) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= chars) {
                    break;
                }
            }
            return hex.substring(0, chars);
        } catch (java.security.NoSuchAlgorithmException e) {
            // FNV-1a fallback — still deterministic, collision-safe enough for name clamping
            long fnv = 0xcbf29ce484222325L;
            for (int i = 0; i < value.length(); i++) {
                fnv ^= value.charAt(i);
                fnv *= 0x100000001b3L;
            }
            return String.format("%016x", fnv).substring(0, chars);
        }
    }

    static String mcpToolsetName(String serverName) {
        return "mcp-" + String.valueOf(serverName == null ? "" : serverName);
    }

    private static String mcpToolDescription(String fullName, McpSchema.Tool tool) {
        String description = tool.description();
        if (description != null && !description.isBlank()) {
            return stripUnicodeTags(description);
        }
        return stripUnicodeTags("MCP tool " + tool.name() + " from " + inferMcpServerName(fullName));
    }

    private static String inferMcpServerName(String fullName) {
        String name = fullName == null ? "" : fullName;
        if (name.startsWith(MCP_TOOL_NAME_PREFIX)) {
            name = name.substring(MCP_TOOL_NAME_PREFIX.length());
        }
        int delimiter = name.indexOf(MCP_NAME_DELIMITER);
        return delimiter > 0 ? name.substring(0, delimiter) : "server";
    }

    private Map<String, Object> parseObjectArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(arguments, Object.class);
            return objectMap(parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + e.getMessage(), e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize MCP utility result", e);
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return result;
    }

    private static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static Map<String, Object> stripReservedMetaKeys(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            if (key != null && !isReservedMetaKey(key)) {
                result.put(key, entry.getValue());
            }
        }
        return result.isEmpty() ? null : result;
    }

    static boolean isReservedMetaKey(String key) {
        int slash = key == null ? -1 : key.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String[] labels = key.substring(0, slash).split("\\.");
        for (int i = 0; i < labels.length; i++) {
            if (("modelcontextprotocol".equals(labels[i]) || "mcp".equals(labels[i]))
                && i < labels.length - 1) {
                return true;
            }
        }
        return false;
    }

    private static String renderContentBlocks(List<McpSchema.Content> contents, String serverName, MediaPublisher publisher) {
        return safeList(contents).stream()
            .map(content -> contentText(content, serverName, publisher))
            .filter(text -> text != null && !text.isBlank())
            .collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> resourceEntry(McpSchema.Resource resource) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfText(entry, "uri", resource.uri());
        putIfText(entry, "name", resource.name());
        putIfText(entry, "description", resource.description());
        putIfText(entry, "mimeType", resource.mimeType());
        return entry;
    }

    private static Map<String, Object> promptEntry(McpSchema.Prompt prompt) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfText(entry, "name", prompt.name());
        putIfText(entry, "description", prompt.description());
        if (prompt.arguments() != null && !prompt.arguments().isEmpty()) {
            entry.put("arguments", prompt.arguments().stream()
                .map(McpLifecycleManager::promptArgumentEntry)
                .toList());
        }
        return entry;
    }

    private static Map<String, Object> promptArgumentEntry(McpSchema.PromptArgument argument) {
        Map<String, Object> entry = new LinkedHashMap<>();
        putIfText(entry, "name", argument.name());
        putIfText(entry, "description", argument.description());
        if (argument.required() != null) {
            entry.put("required", argument.required());
        }
        return entry;
    }

    private static Map<String, Object> promptMessageEntry(McpSchema.PromptMessage message, MediaPublisher publisher) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (message.role() != null) {
            entry.put("role", message.role().name().toLowerCase(Locale.ROOT));
        }
        if (message.content() != null) {
            entry.put("content", contentText(message.content(), "", publisher));
        }
        return entry;
    }

    private static String contentText(McpSchema.Content content, String serverName, MediaPublisher publisher) {
        if (content == null) {
            return "";
        }
        if (content instanceof McpSchema.TextContent text) {
            return stripUnicodeTags(text.text());
        }
        if (content instanceof McpSchema.ImageContent image) {
            return publishMcpImageContent(image, publisher);
        }
        if (content instanceof McpSchema.AudioContent audio) {
            return publishMcpAudioContent(audio, publisher);
        }
        if (content instanceof McpSchema.ResourceLink link) {
            return resourceLinkText(link, serverName);
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            return resourceContentsText(embedded.resource(), publisher);
        }
        return stripUnicodeTags(content.toString());
    }

    static String mcpImageExtensionForMimeType(String mimeType) {
        String normalized = normalizedMimeType(mimeType);
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp", "image/x-ms-bmp" -> ".bmp";
            default -> ".png";
        };
    }

    static String publishMcpImageContent(McpSchema.ImageContent image, MediaPublisher publisher) {
        if (image == null || image.data() == null || image.data().isBlank()) {
            return "";
        }
        String normalizedMime = normalizedMimeType(image.mimeType());
        if (!normalizedMime.startsWith("image/")) {
            return "";
        }
        if (image.data().length() > MCP_RESOURCE_MAX_B64_CHARS) {
            long approximateBytes = image.data().length() * 3L / 4L;
            return "[MCP image resource too large to cache: ~" + approximateBytes + " bytes]";
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(image.data());
        } catch (IllegalArgumentException e) {
            log.warn("MCP image block decode failed ({}): {}", normalizedMime, e.toString());
            return "";
        }
        if (bytes.length > MCP_RESOURCE_MAX_BYTES) {
            return "[MCP image resource too large to cache: " + bytes.length + " bytes]";
        }
        if (!looksLikeImage(bytes)) {
            return "";
        }
        String extension = mcpImageExtensionForMimeType(normalizedMime);
        if (publisher != null) return publisher.publish("photo", normalizedMime, "mcp" + extension, bytes);
        return cacheMcpMediaTag(bytes, "images", extension);
    }

    static String mcpAudioExtensionForMimeType(String mimeType) {
        String normalized = normalizedMimeType(mimeType);
        return switch (normalized) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/webm" -> ".webm";
            case "audio/flac", "audio/x-flac" -> ".flac";
            case "audio/aac" -> ".aac";
            case "audio/mp4", "audio/x-m4a" -> ".m4a";
            default -> ".ogg";
        };
    }

    static String publishMcpAudioContent(McpSchema.AudioContent audio, MediaPublisher publisher) {
        if (audio == null || audio.data() == null || audio.data().isBlank()) {
            return "";
        }
        String normalizedMime = normalizedMimeType(audio.mimeType());
        if (!normalizedMime.startsWith("audio/")) {
            return "";
        }
        if (audio.data().length() > MCP_RESOURCE_MAX_B64_CHARS) {
            long approximateBytes = audio.data().length() * 3L / 4L;
            return "[MCP audio resource too large to cache: ~" + approximateBytes + " bytes]";
        }
        byte[] bytes = decodeMcpBase64Resource(audio.data(), "audio", normalizedMime);
        if (bytes == null) {
            return "";
        }
        if (bytes.length > MCP_RESOURCE_MAX_BYTES) {
            return "[MCP audio resource too large to cache: " + bytes.length + " bytes]";
        }
        String extension = mcpAudioExtensionForMimeType(normalizedMime);
        if (publisher != null) return publisher.publish("audio", normalizedMime, "mcp" + extension, bytes);
        return cacheMcpMediaTag(bytes, "audio", extension);
    }

    private static String cacheMcpMediaTag(byte[] bytes, String category, String extension) {
        try {
            return "MEDIA:" + cacheMcpBytes(bytes, category, "mcp-" + UUID.randomUUID() + extension);
        } catch (java.io.IOException e) {
            log.warn("MCP {} cache write failed: {}", category, e.toString());
            return "";
        }
    }

    private static String normalizedMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return false;
        }
        if (startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return true;
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
            && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }
        if (startsWith(bytes, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            || startsWith(bytes, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return true;
        }
        if (bytes.length >= 12 && startsWith(bytes, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return true;
        }
        return startsWith(bytes, new byte[] {0x42, 0x4D});
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String resourceLinkText(McpSchema.ResourceLink link, String serverName) {
        if (link.uri() == null || link.uri().isBlank()) {
            return "";
        }
        StringBuilder details = new StringBuilder("uri=").append(stripUnicodeTags(link.uri()));
        if (link.name() != null && !link.name().isBlank()) {
            details.append(", name=").append(stripUnicodeTags(link.name()));
        }
        if (link.mimeType() != null && !link.mimeType().isBlank()) {
            details.append(", mimeType=").append(stripUnicodeTags(link.mimeType()));
        }
        String reader = serverName == null || serverName.isBlank()
            ? "the MCP server's read_resource tool"
            : mcpPrefixedToolName(serverName, "read_resource");
        return "[MCP resource link: " + details + " - fetch it with " + reader + "]";
    }

    private static String resourceContentsText(McpSchema.ResourceContents contents, MediaPublisher publisher) {
        if (contents == null) {
            return "";
        }
        if (contents instanceof McpSchema.TextResourceContents text) {
            return stripUnicodeTags(text.text());
        }
        if (contents instanceof McpSchema.BlobResourceContents blob) {
            return renderMcpBlobResourceContents(blob, publisher);
        }
        return stripUnicodeTags(contents.toString());
    }

    static String renderMcpBlobResourceContents(McpSchema.BlobResourceContents blob, MediaPublisher publisher) {
        if (blob == null || blob.blob() == null || blob.blob().isBlank()) {
            return "";
        }
        String uri = blob.uri() == null ? "" : stripUnicodeTags(blob.uri());
        String mimeType = normalizedMimeType(blob.mimeType());
        if (blob.blob().length() > MCP_RESOURCE_MAX_B64_CHARS) {
            long approximateBytes = blob.blob().length() * 3L / 4L;
            return "[MCP embedded resource too large to cache: ~" + approximateBytes + " bytes, uri=" + uri + "]";
        }
        byte[] bytes = decodeMcpBase64Resource(blob.blob(), "embedded resource", mimeType.isBlank() ? uri : mimeType);
        if (bytes == null) {
            return "[MCP embedded resource could not be decoded: " + (mimeType.isBlank() ? uri : mimeType) + "]";
        }
        if (bytes.length > MCP_RESOURCE_MAX_BYTES) {
            return "[MCP embedded resource too large to cache: " + bytes.length + " bytes, uri=" + uri + "]";
        }
        String fileName = mcpResourceFilename(uri, mimeType);
        if (publisher != null) {
            return publisher.publish("document", mimeType.isBlank() ? "application/octet-stream" : mimeType,
                fileName, bytes);
        }
        try {
            Path output = cacheMcpBytes(bytes, "resources", "mcp-" + UUID.randomUUID() + "-" + fileName);
            String detail = mimeType.isBlank() ? "unknown type" : mimeType;
            return "[MCP resource saved to " + output + " (" + detail + ", " + bytes.length
                + " bytes) - read it with read_file or terminal tools]";
        } catch (Exception e) {
            log.warn("MCP embedded resource cache failed: {}", e.toString());
            return "[MCP embedded resource could not be cached: " + (mimeType.isBlank() ? uri : mimeType) + "]";
        }
    }

    static String mcpResourceFilename(String uri, String mimeType) {
        String name = "";
        if (uri != null && !uri.isBlank()) {
            try {
                String path = URI.create(uri).getPath();
                name = lastDecodedPathSegment(path);
            } catch (IllegalArgumentException e) {
                name = lastDecodedPathSegment(uri);
            }
        }
        name = sanitizeCacheFileName(name);
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            name = "resource" + mcpResourceExtensionForMimeType(mimeType);
        }
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 13) {
                String ext = name.substring(dot);
                name = name.substring(0, Math.max(1, 150 - ext.length())) + ext;
            } else {
                name = name.substring(0, 150);
            }
        }
        return name;
    }

    private static String lastDecodedPathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = value;
        }
        String normalized = decoded.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String sanitizeCacheFileName(String name) {
        if (name == null) {
            return "";
        }
        return name
            .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]", "_")
            .trim();
    }

    private static String mcpResourceExtensionForMimeType(String mimeType) {
        String normalized = normalizedMimeType(mimeType);
        if (normalized.startsWith("image/")) {
            return mcpImageExtensionForMimeType(normalized);
        }
        if (normalized.startsWith("audio/")) {
            return mcpAudioExtensionForMimeType(normalized);
        }
        return switch (normalized) {
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "application/zip" -> ".zip";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            default -> ".bin";
        };
    }

    private static byte[] decodeMcpBase64Resource(String data, String kind, String details) {
        if (data.length() > MCP_RESOURCE_MAX_B64_CHARS) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            log.warn("MCP {} block decode failed ({}): {}", kind, details, e.toString());
            return null;
        }
    }

    private static Path cacheMcpBytes(byte[] bytes, String category, String fileName) throws java.io.IOException {
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "java-agent", "mcp-media", category);
        Files.createDirectories(cacheDir);
        Path output = cacheDir.resolve(fileName).toAbsolutePath().normalize();
        if (!output.startsWith(cacheDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Resolved MCP media path escaped cache directory");
        }
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return output;
    }

    private static String stripUnicodeTags(String text) {
        return com.azhukov.agent.core.security.UnicodeTagStripper.stripUnicodeTags(text == null ? "" : text);
    }

    private static void putIfText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private enum McpUtilityAction {
        LIST_RESOURCES,
        READ_RESOURCE,
        LIST_PROMPTS,
        GET_PROMPT
    }

    private class McpUtilityToolHandler implements ToolHandler {
        private final String serverName;
        private final McpUtilityAction action;

        McpUtilityToolHandler(String serverName, McpUtilityAction action) {
            this.serverName = serverName;
            this.action = action;
        }

        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            try {
                return switch (action) {
                    case LIST_RESOURCES -> ToolResult.ok(listResourcesForTool(serverName));
                    case READ_RESOURCE -> ToolResult.ok(readResourceForTool(serverName, arguments, session));
                    case LIST_PROMPTS -> ToolResult.ok(listPromptsForTool(serverName));
                    case GET_PROMPT -> ToolResult.ok(getPromptForTool(serverName, arguments, session));
                };
            } catch (Exception e) {
                return mcpError("MCP tool failed: " + e.getMessage());
            }
        }
    }

    record McpServerState(AgentProperties.McpProperties.ServerProperties properties,
                          McpSyncClient client,
                          List<McpSchema.Tool> tools, List<McpSchema.Tool> admittedTools, boolean registrationComplete) {
        McpServerState(AgentProperties.McpProperties.ServerProperties properties, McpSyncClient client,
                       List<McpSchema.Tool> tools, List<McpSchema.Tool> admittedTools) {
            this(properties, client, tools, admittedTools, true);
        }

        McpServerState(AgentProperties.McpProperties.ServerProperties properties,
                       McpSyncClient client, List<McpSchema.Tool> tools) {
            this(properties, client, tools, tools);
        }
    }

    /** Test seam: direct access to the connected-clients map. */
    Map<String, McpServerState> getClientsForTest() {
        return clients;
    }

    public record McpServerInfo(String name, String baseUrl, String transport, int toolCount, List<String> toolNames) {}

    public record DiscoveredTool(String serverName, String toolName, ToolDefinition definition) {}

    public class McpToolHandler implements ToolHandler {
        private final String serverName;
        private final String toolName;

        McpToolHandler(String serverName, String toolName) {
            this.serverName = serverName;
            this.toolName = toolName;
        }

        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            return execute(arguments, lastAssistant, session, null);
        }

        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session, ToolTrustMetadata admission) {
            // h42: When an MCP server reuses the same tool_call_id for different calls,
            // keep the tool results instead of overwriting/dropping. The tool_call_id is
            // available in the session metadata if needed, but the result is always
            // returned — the caller (DefaultAgentRuntime) is responsible for not
            // overwriting existing results with the same ID.
            String breakerReason = mcpCircuitBreakerBlockReason(serverName);
            if (breakerReason != null) {
                return mcpError(breakerReason);
            }
            // ── Rate limiting ──
            if (rateLimiter != null) {
                String rateLimitKey = serverName + "__" + toolName;
                if (!rateLimiter.tryAcquire(rateLimitKey,
                        properties.getMcp().getRateLimitMaxCalls() > 0 ? properties.getMcp().getRateLimitMaxCalls() : 0,
                        properties.getMcp().getRateLimitWindowSeconds() > 0 ? properties.getMcp().getRateLimitWindowSeconds() : 0)) {
                    return mcpError("Rate limit exceeded for MCP tool: " + toolName);
                }
            }
            // ── Argument injection scan ──
            if (argumentScanner != null) {
                try {
                    Map<String, Object> argsMap = objectMapper.readValue(arguments, new TypeReference<>() {});
                    ScanResult argScan = argumentScanner.scan(argsMap);
                    if (argScan.isBlocked()) {
                        log.warn("Blocking MCP tool '{}' call due to argument injection: {}",
                            toolName, argScan.getThreatDescription());
                        return mcpError("Tool call blocked by security scanner: " + argScan.getThreatDescription());
                    }
                } catch (Exception e) {
                    // If arguments can't be parsed as JSON, proceed — let the tool handler deal with it
                    log.debug("Could not parse tool arguments for scanning: {}", e.getMessage());
                }
            }
            try {
                var result = executeModelTool(serverName, toolName, arguments, admission);
                // ── Response security scan ──
                String output;
                MediaPublisher publisher = publisherFor(session);
                if (Boolean.TRUE.equals(result.isError())) {
                    output = renderContentBlocks(result.content(), serverName, publisher);
                    // H-SYNC: Strip invisible Unicode TAG characters from MCP tool output
                    output = com.azhukov.agent.core.security.UnicodeTagStripper.stripUnicodeTags(output);
                } else {
                    output = formatCallToolResult(result, serverName, publisher);
                }
                String safeOutput = output;
                if (responseScanner != null) {
                    ScanResult responseScan = responseScanner.scan(output);
                    if (!responseScan.isClean()) {
                        log.warn("MCP tool '{}' response had security findings: {}",
                            toolName, responseScan.getThreatDescription());
                    }
                    // Use sanitized text if available
                    safeOutput = responseScan.getSanitizedText() != null ? responseScan.getSanitizedText() : output;
                }
                if (Boolean.TRUE.equals(result.isError())) {
                    bumpMcpServerError(serverName);
                    return mcpError(safeOutput);
                }
                resetMcpServerError(serverName);
                return ToolResult.ok(safeOutput);
            } catch (Exception e) {
                bumpMcpServerError(serverName);
                return mcpError("MCP tool failed: " + e.getMessage());
            }
        }
    }

    // ── Security (inline-script validation, kept from hardening pass) ──
private static final Set<String> SHELL_INTERPRETERS = Set.of(
        "bash", "sh", "zsh", "dash", "fish", "cmd", "cmd.exe",
        "powershell", "powershell.exe", "pwsh", "pwsh.exe"
    );
private static final java.util.regex.Pattern EGRESS_PATTERN = java.util.regex.Pattern.compile(
        "(?<![\\w.-])(?:curl|wget|nc|ncat|socat)(?![\\w.-])"
        + "|/dev/tcp/"
        + "|\\bInvoke-WebRequest\\b"
        + "|\\bInvoke-RestMethod\\b"
        + "|\\bSystem\\.Net\\.WebClient\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );
private static final java.util.regex.Pattern PERSISTENCE_PATTERN = java.util.regex.Pattern.compile(
        "authorized_keys"
        + "|\\.ssh/"
        + "|/etc/ssh\\b"
        + "|/etc/pam\\.d\\b|pam_[\\w-]+\\.so"
        + "|/etc/sudoers"
        + "|/etc/cron|crontab\\b"
        + "|/etc/rc\\.local|/etc/systemd"
        + "|\\.bashrc\\b|\\.bash_profile\\b|\\.profile\\b|\\.zshrc\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );

    /**
     * rev-125 Hermes parity (mcp_security.py validate_mcp_server_entry): when
     * the command is a shell interpreter, its inline script (the args) must
     * not perform network egress (curl/wget/nc/socat//dev/tcp/PowerShell web
     * cmdlets) nor write to OS persistence surfaces (SSH keys, PAM, sudoers,
     * cron, systemd, shell rc files). Legitimate non-shell MCP commands are
     * unaffected — this only constrains interpreter-shaped entries.
     *
     * @param command the MCP server command
     * @param args    the args passed to the subprocess (the inline script)
     * @return null if acceptable, or a block reason
     */
    static String validateInlineScript(String command, java.util.List<String> args) {
        if (command == null || args == null || args.isEmpty()) {
            return null;
        }
        String basename = java.nio.file.Paths.get(command.trim()).getFileName().toString().toLowerCase();
        if (!SHELL_INTERPRETERS.contains(basename)) {
            return null;
        }
        String inline = String.join(" ", args);
        if (EGRESS_PATTERN.matcher(inline).find()) {
            return "shell interpreter with inline network egress (curl/wget/nc/socat) refused";
        }
        if (PERSISTENCE_PATTERN.matcher(inline).find()) {
            return "shell interpreter writing to an OS persistence surface (SSH/PAM/sudoers/cron/rc) refused";
        }
        return null;
    }
}
