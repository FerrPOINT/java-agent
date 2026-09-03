package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
import com.azhukov.agent.core.security.McpToolTrustService;
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
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.time.Duration;
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
    private final Map<String, McpServerState> clients = new ConcurrentHashMap<>();
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

    public void connectConfiguredServers() {
        // A reload must be able to reconnect after closeAll() flipped the latch.
        shutdownRequested.set(false);
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP is disabled.");
            return;
        }
        for (AgentProperties.McpProperties.ServerProperties server : properties.getMcp().getServers()) {
            if (!server.isEnabled()) {
                log.info("Skipping disabled MCP server {}", server.getName());
                continue;
            }
            connect(server);
        }
    }

    public void connect(AgentProperties.McpProperties.ServerProperties server) {
        // H18: Synchronize only the check-then-act, not the initialization.
        // client.initialize() and listToolsWithPagination() involve network I/O
        // that must NOT be held under the lock — it would block all other connect/
        // reconnect operations for the entire duration of the handshake.
        synchronized (clients) {
            if (clients.containsKey(server.getName())) {
                return;
            }
        }
        McpSyncClient client = null;
        try {
            client = createClient(server);
            client.initialize();
            // h43: Follow nextCursor pagination when listing tools.
            var tools = listToolsWithPagination(client);
            // H18: Re-check under lock to prevent duplicate connections from
            // concurrent callers that both passed the initial check.
            synchronized (clients) {
                if (clients.containsKey(server.getName())) {
                    // Another thread already connected — close this duplicate client.
                    safeCloseClient(client);
                    return;
                }
                clients.put(server.getName(), new McpServerState(server, client, tools));
            }
            registerTools(server.getName(), client, tools);
            scheduleToolRefresh(server.getName());
            resetMcpServerError(server.getName());
            log.info("Connected to MCP server {} ({}) with {} tools", server.getName(), server.getTransport(), tools.size());
        } catch (Exception e) {
            log.warn("Failed to connect to MCP server {}: {}", server.getName(), e.getMessage());
            // H17: Close the client in the catch block before scheduleReconnect
            // to avoid leaking the underlying transport/resources.
            if (client != null) {
                safeCloseClient(client);
            }
            scheduleReconnect(server, 0, true);
        }
    }

    private void safeCloseClient(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("Error closing MCP client: {}", e.getMessage());
        }
    }

    // h43: Follow nextCursor pagination when listing tools from an MCP server.
    // Hermes parity (tools/mcp_tool.py _drain_paginated): pass the cursor INTO the next
    // list call — calling listTools() without the cursor refetches page 1 forever.
    // Safety-capped at 100 pages to survive a misbehaving server looping its cursor.
    private List<McpSchema.Tool> listToolsWithPagination(McpSyncClient client) {
        List<McpSchema.Tool> allTools = new ArrayList<>();
        try {
            var result = client.listTools();
            if (result.tools() != null) {
                allTools.addAll(result.tools());
            }
            String cursor = result.nextCursor();
            int maxPages = 100; // Safety limit (Hermes has the same class of cap)
            while (cursor != null && !cursor.isEmpty() && maxPages-- > 0) {
                log.debug("MCP tool pagination: fetching next page with cursor '{}'", cursor);
                try {
                    var nextResult = client.listTools(cursor);
                    if (nextResult.tools() != null) {
                        allTools.addAll(nextResult.tools());
                    }
                    String next = nextResult.nextCursor();
                    // Guard against a server returning the same cursor forever.
                    if (cursor.equals(next)) {
                        log.warn("MCP tool pagination: server returned the same cursor twice — stopping");
                        break;
                    }
                    cursor = next;
                } catch (Exception e) {
                    log.warn("MCP tool pagination: failed to fetch next page: {}", e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("MCP tool listing failed: {}", e.getMessage());
        }
        return allTools;
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

    private McpSyncClient createClient(AgentProperties.McpProperties.ServerProperties server) {
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
        ServerParameters.Builder paramsBuilder = ServerParameters.builder(command)
            .args(server.getArgs());
        // Build filtered environment for stdio subprocess
        Map<String, String> filteredEnv = buildSafeEnv(server.getEnv());
        if (!filteredEnv.isEmpty()) {
            paramsBuilder.env(filteredEnv);
        }
        ServerParameters params = paramsBuilder.build();
        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
        return McpClient.sync(transport).build();
    }

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
    private void scheduleReconnect(AgentProperties.McpProperties.ServerProperties server, int attempt, boolean initial) {
        int maxRetries = initial ? MAX_INITIAL_CONNECT_RETRIES : MAX_RECONNECT_RETRIES;
        if (attempt >= maxRetries) {
            log.warn("MCP server {} failed after {} {} attempts, giving up",
                server.getName(), maxRetries, initial ? "initial connect" : "reconnect");
            return;
        }
        long backoffSeconds = Math.min((long) (1L << attempt), MAX_BACKOFF_SECONDS);
        log.info("MCP server {} {} attempt {}/{} in {}s",
            server.getName(), initial ? "initial connect" : "reconnect", attempt + 1, maxRetries, backoffSeconds);
        reconnectExecutor.schedule(() -> {
            if (shutdownRequested.get()) {
                return;
            }
            try {
                McpSyncClient client = createClient(server);
                client.initialize();
                var tools = listToolsWithPagination(client);
                // WARNING 4: Synchronize the read-compare-write to prevent duplicate registrations
                synchronized (clients) {
                    clients.put(server.getName(), new McpServerState(server, client, tools));
                }
                registerTools(server.getName(), client, tools);
                scheduleToolRefresh(server.getName());
                resetMcpServerError(server.getName());
                log.info("Reconnected to MCP server {} with {} tools", server.getName(), tools.size());
            } catch (Exception e) {
                log.warn("MCP server {} {} attempt {}/{} failed: {}",
                    server.getName(), initial ? "initial connect" : "reconnect", attempt + 1, maxRetries, e.getMessage());
                scheduleReconnect(server, attempt + 1, initial);
            }
        }, backoffSeconds, TimeUnit.SECONDS);
    }

    public void reconnect(String serverName) {
        AgentProperties.McpProperties.ServerProperties serverProps = properties.getMcp().getServers().stream()
            .filter(s -> s.getName().equals(serverName))
            .findFirst()
            .orElse(null);
        if (serverProps == null) {
            log.warn("Cannot reconnect unknown MCP server: {}", serverName);
            return;
        }
        // Close existing connection if any
        var existing = clients.remove(serverName);
        if (existing != null) {
            try {
                existing.client().close();
            } catch (Exception e) {
                log.debug("Error closing existing MCP client before reconnect: {}", e.getMessage());
            }
        }
        // Cancel and remove any pending tool refresh task for this server
        ScheduledFuture<?> oldRefresh = toolRefreshFutures.remove(serverName);
        if (oldRefresh != null) {
            oldRefresh.cancel(false);
        }
        scheduleReconnect(serverProps, 0, false);
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
        ScheduledFuture<?> future = toolRefreshExecutor.scheduleWithFixedDelay(() -> {
            try {
                refreshTools(serverName);
            } catch (Exception e) {
                log.warn("Tool refresh for MCP server {} failed: {}", serverName, e.getMessage());
            }
        }, TOOL_REFRESH_INTERVAL_SECONDS, TOOL_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        toolRefreshFutures.put(serverName, future);
    }

    public void refreshTools(String serverName) {
        var state = clients.get(serverName);
        if (state == null) {
            return;
        }
        try {
            var freshTools = listToolsWithPagination(state.client());
            List<McpSchema.Tool> oldRegisteredTools = selectedMcpTools(state.properties(), state.tools());
            List<McpSchema.Tool> freshRegisteredTools = selectedMcpTools(state.properties(), freshTools);
            recordMcpToolTrustMetadata(serverName, state.properties(), freshRegisteredTools);
            // Finding 8.1: Compare tool inputSchema JSON, not just names.
            // A schema change (e.g. new required parameter) with the same name
            // must trigger re-registration to avoid stale schemas being sent to the model.
            boolean schemasChanged = schemasDiffer(oldRegisteredTools, freshRegisteredTools);
            Set<String> oldNames = oldRegisteredTools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            Set<String> newNames = freshRegisteredTools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            if (oldNames.equals(newNames) && !schemasChanged) {
                return; // No changes detected
            }
            if (schemasChanged && oldNames.equals(newNames)) {
                log.info("MCP server {} tool schema changed (names unchanged): {} tools", serverName, newNames.size());
            } else {
                log.info("MCP server {} tool list changed: {} -> {} tools", serverName, oldNames.size(), newNames.size());
            }
            // Deregister stale tools
            for (String oldName : oldNames) {
                if (!newNames.contains(oldName)) {
                    String fullName = mcpPrefixedToolName(serverName, oldName);
                    toolRegistry().deregisterDynamic(fullName);
                    if (fingerprintStore != null) fingerprintStore.remove(fullName);
                    log.info("Deregistered stale MCP tool: {}", fullName);
                }
            }
            // Register new/updated tools
            for (McpSchema.Tool tool : freshRegisteredTools) {
                String fullName = mcpPrefixedToolName(serverName, tool.name());
                // ── Security scan: check tool definition before registering ──
                Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
                ScanResult defScan = definitionScanner.scan(fullName, tool.description(), schema);
                if (defScan.isBlocked()) {
                    log.warn("Blocking registration of MCP tool '{}' due to security findings: {}",
                        fullName, defScan.getThreatDescription());
                    continue;
                }
                if (!defScan.isClean()) {
                    log.warn("Registering MCP tool '{}' with security warnings: {}",
                        fullName, defScan.getThreatDescription());
                }
                // ── Rug pull detection: check fingerprint ──
                fingerprintStore.recordFingerprint(fullName, tool.description(), schema);
                ToolDefinition definition = convertToolDefinition(fullName, tool);
                toolRegistry().registerDynamic(fullName, mcpToolsetName(serverName),
                    definition, new McpToolHandler(serverName, tool.name()));
            }
            registerUtilityTools(serverName, state.client(), newNames.stream()
                .map(name -> mcpPrefixedToolName(serverName, name))
                .collect(Collectors.toSet()));
            // Update state atomically (Finding 8.3: synchronize replacement to prevent
            // in-flight tool calls from referencing stale tool definitions)
            synchronized (clients) {
                clients.put(serverName, new McpServerState(state.properties(), state.client(), freshTools));
            }
            Set<String> added = newNames.stream().filter(n -> !oldNames.contains(n)).collect(Collectors.toSet());
            Set<String> removed = oldNames.stream().filter(n -> !newNames.contains(n)).collect(Collectors.toSet());
            if (!added.isEmpty()) {
                log.info("MCP server {} added tools: {}", serverName, added);
            }
            if (!removed.isEmpty()) {
                log.info("MCP server {} removed tools: {}", serverName, removed);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh tools from MCP server {}: {}", serverName, e.getMessage());
            // Finding 8.2: A failed tool refresh may indicate the server is unhealthy.
            // Trigger a reconnect if the error looks like a connection failure.
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            boolean isConnectionError = msg.contains("connection") || msg.contains("closed")
                || msg.contains("disconnected") || msg.contains("refused")
                || msg.contains("reset") || msg.contains("timeout");
            if (isConnectionError) {
                log.info("MCP server {} tool refresh failed with connection error, triggering reconnect", serverName);
                properties.getMcp().getServers().stream()
                    .filter(s -> s.getName().equals(serverName))
                    .findFirst()
                    .ifPresent(server -> {
                        clients.remove(serverName);
                        ScheduledFuture<?> oldRefresh = toolRefreshFutures.remove(serverName);
                        if (oldRefresh != null) {
                            oldRefresh.cancel(false);
                        }
                        scheduleReconnect(server, 0, false);
                    });
            }
        }
    }

    /**
     * Finding 8.1: Compare tool inputSchema JSON between old and fresh tool lists.
     * Returns true if any tool's schema has changed (even if the name is the same).
     */
    private boolean schemasDiffer(List<McpSchema.Tool> oldTools, List<McpSchema.Tool> freshTools) {
        Map<String, String> oldSchemas = new LinkedHashMap<>();
        for (McpSchema.Tool t : oldTools) {
            oldSchemas.put(t.name(), schemaToComparableJson(t));
        }
        for (McpSchema.Tool t : freshTools) {
            String oldJson = oldSchemas.get(t.name());
            if (oldJson == null) continue; // new tool — handled by name diff
            String freshJson = schemaToComparableJson(t);
            if (!oldJson.equals(freshJson)) {
                log.debug("Schema changed for tool {}: old={}, new={}", t.name(), oldJson, freshJson);
                return true;
            }
        }
        return false;
    }

    /** Convert a tool's inputSchema to a comparable JSON string. */
    private String schemaToComparableJson(McpSchema.Tool tool) {
        try {
            Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            // If serialization fails, compare by toString as fallback
            return String.valueOf(tool.inputSchema());
        }
    }

    private void registerTools(String serverName, McpSyncClient client, List<McpSchema.Tool> tools) {
        var state = clients.get(serverName);
        AgentProperties.McpProperties.ServerProperties server = state == null ? null : state.properties();
        List<McpSchema.Tool> registeredTools = selectedMcpTools(server, tools);
        recordMcpToolTrustMetadata(serverName, server, registeredTools);
        Set<String> nativeFullNames = registeredTools.stream()
            .map(tool -> mcpPrefixedToolName(serverName, tool.name()))
            .collect(Collectors.toSet());
        for (McpSchema.Tool tool : registeredTools) {
            String fullName = mcpPrefixedToolName(serverName, tool.name());
            // ── Security scan: check tool definition before registering ──
            Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
            if (definitionScanner != null) {
                ScanResult defScan = definitionScanner.scan(fullName, tool.description(), schema);
                if (defScan.isBlocked()) {
                    log.warn("Blocking registration of MCP tool '{}' due to security findings: {}",
                        fullName, defScan.getThreatDescription());
                    continue;
                }
                if (!defScan.isClean()) {
                    log.warn("Registering MCP tool '{}' with security warnings: {}",
                        fullName, defScan.getThreatDescription());
                }
            }
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
        registerUtilityTools(serverName, client, nativeFullNames);
    }

    private List<McpSchema.Tool> selectedMcpTools(AgentProperties.McpProperties.ServerProperties server,
                                                  List<McpSchema.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
            .filter(tool -> tool != null && shouldRegisterMcpTool(server, tool.name()))
            .toList();
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

    private void registerUtilityTools(String serverName, McpSyncClient client, Set<String> nativeFullNames) {
        McpSchema.ServerCapabilities capabilities = safeServerCapabilities(serverName, client);
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
        recordGeneratedMcpUtilityToolTrustMetadata(serverName, fullName);
    }

    private void recordMcpToolTrustMetadata(String serverName,
                                            AgentProperties.McpProperties.ServerProperties server,
                                            List<McpSchema.Tool> tools) {
        if (mcpToolTrustService == null || serverName == null || tools == null) {
            return;
        }
        Map<String, Boolean> readOnlyByFullName = new LinkedHashMap<>();
        for (McpSchema.Tool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            readOnlyByFullName.put(mcpPrefixedToolName(serverName, tool.name()), isReadOnlyHint(tool));
        }
        mcpToolTrustService.recordServerTools(serverName, server == null ? null : server.getTrust(), readOnlyByFullName);
    }

    private void recordGeneratedMcpUtilityToolTrustMetadata(String serverName, String fullName) {
        if (mcpToolTrustService == null) {
            return;
        }
        McpServerState state = clients.get(serverName);
        AgentProperties.McpProperties.ServerProperties server = state == null ? null : state.properties();
        mcpToolTrustService.recordTool(serverName, server == null ? null : server.getTrust(), fullName, true);
    }

    static boolean isReadOnlyHint(McpSchema.Tool tool) {
        return tool != null
            && tool.annotations() != null
            && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
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
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            var result = callMcpWithTimeout(state, "read_resource '" + uri + "'",
                () -> state.client().readResource(new McpSchema.ReadResourceRequest(uri)));
            return safeList(result.contents()).stream()
                .map(McpLifecycleManager::resourceContentsText)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("MCP read resource failed: " + sanitizeError(e.getMessage()), e);
        }
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

    String readResourceForTool(String serverName, String arguments) {
        Map<String, Object> args = parseObjectArguments(arguments);
        Object uri = args.get("uri");
        if (uri == null || uri.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required parameter 'uri'");
        }
        return writeJson(Map.of("result", readResource(serverName, uri.toString())));
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

    String getPromptForTool(String serverName, String arguments) {
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
            response.put("messages", safeList(result.messages()).stream()
                .map(McpLifecycleManager::promptMessageEntry)
                .toList());
            return writeJson(response);
        } catch (Exception e) {
            throw new RuntimeException("MCP get prompt failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    public String formatCallToolResult(McpSchema.CallToolResult result, String serverName) {
        String text = renderContentBlocks(result == null ? null : result.content(), serverName);
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
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            try {
                return callToolWithTimeout(state, toolName, args);
            } catch (Exception callError) {
                if (!isAuthError(callError)) {
                    throw callError;
                }
                recoverOAuthOrThrow(serverName, callError);
                try {
                    var retryState = clients.getOrDefault(serverName, state);
                    return callToolWithTimeout(retryState, toolName, args);
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

    private McpSchema.CallToolResult callToolWithTimeout(McpServerState state,
                                                         String toolName,
                                                         Map<String, Object> args) throws Exception {
        return callMcpWithTimeout(state, "tool '" + toolName + "'",
            () -> state.client().callTool(new McpSchema.CallToolRequest(toolName, args)));
    }

    private <T> T callMcpWithTimeout(McpServerState state, String operation, Callable<T> action) throws Exception {
        Duration timeout = resolveMcpToolTimeout(state == null ? null : state.properties());
        Future<T> future = toolCallExecutor.submit(action);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
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

    private boolean isAuthError(Throwable error) {
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

    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        // Reload-safe shutdown: terminators ran on the OLD pools; fresh pools are
        // created so connectConfiguredServers() works after a reload (reload-mcp
        // calls closeAll() then reconnects — one-shot shutdown made every later
        // schedule throw RejectedExecutionException).
        shutdownRequested.set(true);
        reconnectExecutor.shutdownNow();
        toolRefreshExecutor.shutdownNow();
        toolCallExecutor.shutdownNow();
        reconnectExecutor = newReconnectExecutor();
        toolRefreshExecutor = newToolRefreshExecutor();
        toolCallExecutor = newToolCallExecutor();
        // Cancel all tool refresh futures
        for (ScheduledFuture<?> f : toolRefreshFutures.values()) {
            f.cancel(false);
        }
        toolRefreshFutures.clear();
        for (var state : clients.values()) {
            try {
                state.client().close();
            } catch (Exception e) {
                log.debug("Error closing MCP client: {}", e.getMessage());
            }
        }
        clients.clear();
        if (fingerprintStore != null) fingerprintStore.clear();
        if (rateLimiter != null) rateLimiter.clear();
        mcpServerErrorCounts.clear();
        mcpServerBreakerOpenedAtNanos.clear();
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

    static String mcpPrefixedToolName(String serverName, String toolName) {
        return MCP_TOOL_NAME_PREFIX + sanitizeMcpNameComponent(serverName)
            + MCP_NAME_DELIMITER + sanitizeMcpNameComponent(toolName);
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

    private static String renderContentBlocks(List<McpSchema.Content> contents, String serverName) {
        return safeList(contents).stream()
            .map(content -> contentText(content, serverName))
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

    private static Map<String, Object> promptMessageEntry(McpSchema.PromptMessage message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (message.role() != null) {
            entry.put("role", message.role().name().toLowerCase(Locale.ROOT));
        }
        if (message.content() != null) {
            entry.put("content", contentText(message.content()));
        }
        return entry;
    }

    private static String contentText(McpSchema.Content content) {
        return contentText(content, "");
    }

    private static String contentText(McpSchema.Content content, String serverName) {
        if (content == null) {
            return "";
        }
        if (content instanceof McpSchema.TextContent text) {
            return stripUnicodeTags(text.text());
        }
        if (content instanceof McpSchema.ImageContent image) {
            return cacheMcpImageContent(image);
        }
        if (content instanceof McpSchema.AudioContent audio) {
            return cacheMcpAudioContent(audio);
        }
        if (content instanceof McpSchema.ResourceLink link) {
            return resourceLinkText(link, serverName);
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            return resourceContentsText(embedded.resource());
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

    static String cacheMcpImageContent(McpSchema.ImageContent image) {
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
        try {
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "java-agent", "mcp-media", "images");
            Files.createDirectories(cacheDir);
            String filename = "mcp-" + UUID.randomUUID() + mcpImageExtensionForMimeType(normalizedMime);
            Path output = cacheDir.resolve(filename).toAbsolutePath().normalize();
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return "MEDIA:" + output;
        } catch (Exception e) {
            log.warn("MCP image block cache failed: {}", e.toString());
            return "";
        }
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

    static String cacheMcpAudioContent(McpSchema.AudioContent audio) {
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
        try {
            Path output = cacheMcpBytes(bytes, "audio", "mcp-" + UUID.randomUUID() + mcpAudioExtensionForMimeType(normalizedMime));
            return "MEDIA:" + output;
        } catch (Exception e) {
            log.warn("MCP audio block cache failed: {}", e.toString());
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

    private static String resourceContentsText(McpSchema.ResourceContents contents) {
        if (contents == null) {
            return "";
        }
        if (contents instanceof McpSchema.TextResourceContents text) {
            return stripUnicodeTags(text.text());
        }
        if (contents instanceof McpSchema.BlobResourceContents blob) {
            return renderMcpBlobResourceContents(blob);
        }
        return stripUnicodeTags(contents.toString());
    }

    static String renderMcpBlobResourceContents(McpSchema.BlobResourceContents blob) {
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
        try {
            String fileName = mcpResourceFilename(uri, mimeType);
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
                    case READ_RESOURCE -> ToolResult.ok(readResourceForTool(serverName, arguments));
                    case LIST_PROMPTS -> ToolResult.ok(listPromptsForTool(serverName));
                    case GET_PROMPT -> ToolResult.ok(getPromptForTool(serverName, arguments));
                };
            } catch (Exception e) {
                return mcpError("MCP tool failed: " + e.getMessage());
            }
        }
    }

    private record McpServerState(AgentProperties.McpProperties.ServerProperties properties,
                                  McpSyncClient client,
                                  List<McpSchema.Tool> tools) {}

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
                var result = executeTool(serverName, toolName, arguments);
                // ── Response security scan ──
                String output;
                if (Boolean.TRUE.equals(result.isError())) {
                    output = renderContentBlocks(result.content(), serverName);
                    // H-SYNC: Strip invisible Unicode TAG characters from MCP tool output
                    output = com.azhukov.agent.core.security.UnicodeTagStripper.stripUnicodeTags(output);
                } else {
                    output = formatCallToolResult(result, serverName);
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
                // Only reconnect on actual connection failures, not tool execution errors.
                // Tool execution errors (e.g. bad arguments, server-side logic errors) should
                // NOT trigger a reconnect — that would unnecessarily tear down a working connection.
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isConnectionError = msg.contains("connection") || msg.contains("closed")
                    || msg.contains("disconnected") || msg.contains("refused")
                    || msg.contains("reset") || msg.contains("not connected")
                    || msg.contains("timeout") || msg.contains("timed out");
                if (isConnectionError) {
                    log.warn("MCP tool '{}' on server '{}' failed with connection error, triggering reconnect: {}",
                        toolName, serverName, e.getMessage());
                    // Find the server properties and schedule a reconnect
                    properties.getMcp().getServers().stream()
                        .filter(s -> s.getName().equals(serverName))
                        .findFirst()
                        .ifPresent(server -> {
                            // Remove stale client entry
                            clients.remove(serverName);
                            // Cancel and remove any pending tool refresh task
                            ScheduledFuture<?> oldRefresh = toolRefreshFutures.remove(serverName);
                            if (oldRefresh != null) {
                                oldRefresh.cancel(false);
                            }
                            scheduleReconnect(server, 0, false);
                        });
                }
                return mcpError("MCP tool failed: " + e.getMessage());
            }
        }
    }
}
