package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.McpResponseScanner;
import com.azhukov.agent.core.security.McpToolDefinitionScanner;
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
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ReentrantLock clientsLock = new ReentrantLock();

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

    private volatile ScheduledExecutorService reconnectExecutor = newReconnectExecutor();
    private volatile ScheduledExecutorService toolRefreshExecutor = newToolRefreshExecutor();

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
            connect(server);
        }
    }

    public void connect(AgentProperties.McpProperties.ServerProperties server) {
        // H18: Synchronize only the check-then-act, not the initialization.
        // client.initialize() and listToolsWithPagination() involve network I/O
        // that must NOT be held under the lock — it would block all other connect/
        // reconnect operations for the entire duration of the handshake.
        clientsLock.lock();
        try {
            if (clients.containsKey(server.getName())) {
                return;
            }
        } finally {
            clientsLock.unlock();
        }
        McpSyncClient client = null;
        try {
            client = createClient(server);
            client.initialize();
            // h43: Follow nextCursor pagination when listing tools.
            var tools = listToolsWithPagination(client);
            // H18: Re-check under lock to prevent duplicate connections from
            // concurrent callers that both passed the initial check.
            clientsLock.lock();
            try {
                if (clients.containsKey(server.getName())) {
                    // Another thread already connected — close this duplicate client.
                    safeCloseClient(client);
                    return;
                }
                clients.put(server.getName(), new McpServerState(server, client, tools));
            } finally {
                clientsLock.unlock();
            }
            registerTools(server.getName(), tools);
            scheduleToolRefresh(server.getName());
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
        // rev-125 Hermes parity (mcp_security.py validate_mcp_server_entry):
        // shell interpreters with an inline script that performs network
        // egress or writes to OS persistence surfaces are refused. The args
        // were previously passed to the subprocess completely unvalidated —
        // 'bash' is a clean command, but args=['-c','curl evil.sh'] exfiltrated.
        String inlineScriptError = validateInlineScript(command, server.getArgs());
        if (inlineScriptError != null) {
            throw new IllegalArgumentException(
                "MCP server " + server.getName() + " blocked: " + inlineScriptError);
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
        var transport = HttpClientSseClientTransport.builder(server.getBaseUrl()).build();
        return McpClient.sync(transport).build();
    }

    private McpSyncClient createStreamableHttpClient(AgentProperties.McpProperties.ServerProperties server) {
        var transport = HttpClientStreamableHttpTransport.builder(server.getBaseUrl())
            .build();
        return McpClient.sync(transport).build();
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

    // ── Stdio command validation ─────────────────────────────────────────
    private static final Set<String> SHELL_METACHARACTERS = Set.of(
        ";", "|", "&", "&&", "||", "`", "$(", "$", "(", ")", "{", "}", "<", ">",
        "\n", "\r", "\\"
    );

    // ── rev-125: Hermes mcp_security.py parity ───────────────────────────
    private static final Set<String> SHELL_INTERPRETERS = Set.of(
        "bash", "sh", "zsh", "dash", "fish", "cmd", "cmd.exe",
        "powershell", "powershell.exe", "pwsh", "pwsh.exe"
    );

    /**
     * rev-125 Hermes parity (mcp_security.py _EGRESS_PATTERN): network egress
     * tools an inline shell script has no legitimate reason to invoke.
     */
    private static final java.util.regex.Pattern EGRESS_PATTERN = java.util.regex.Pattern.compile(
        "(?<![\\w.-])(?:curl|wget|nc|ncat|socat)(?![\\w.-])"
        + "|/dev/tcp/"
        + "|\\bInvoke-WebRequest\\b"
        + "|\\bInvoke-RestMethod\\b"
        + "|\\bSystem\\.Net\\.WebClient\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE
    );

    /**
     * rev-125 Hermes parity (mcp_security.py _PERSISTENCE_PATTERN): OS
     * persistence surfaces an MCP server has no legitimate reason to write to
     * (SSH-key/PAM/sudoers/cron/rc-file backdoor shapes).
     */
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
            if (cmdPath.isAbsolute()) {
                java.io.File cmdFile = cmdPath.toFile();
                if (!cmdFile.exists()) {
                    return "Command executable does not exist: " + trimmed;
                }
                if (!cmdFile.canExecute()) {
                    return "Command file is not executable: " + trimmed;
                }
            }
            // For relative paths, rely on PATH resolution at exec time
        }
        return null;
    }

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
                var tools = client.listTools().tools();
                // WARNING 4: Synchronize the read-compare-write to prevent duplicate registrations
                clientsLock.lock();
                try {
                    clients.put(server.getName(), new McpServerState(server, client, tools));
                } finally {
                    clientsLock.unlock();
                }
                registerTools(server.getName(), tools);
                scheduleToolRefresh(server.getName());
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
            var freshTools = state.client().listTools().tools();
            // Finding 8.1: Compare tool inputSchema JSON, not just names.
            // A schema change (e.g. new required parameter) with the same name
            // must trigger re-registration to avoid stale schemas being sent to the model.
            boolean schemasChanged = schemasDiffer(state.tools(), freshTools);
            Set<String> oldNames = state.tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            Set<String> newNames = freshTools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
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
                    String fullName = serverName + "__" + oldName;
                    toolRegistry().deregisterDynamic(fullName);
                    if (fingerprintStore != null) fingerprintStore.remove(fullName);
                    log.info("Deregistered stale MCP tool: {}", fullName);
                }
            }
            // Register new/updated tools
            for (McpSchema.Tool tool : freshTools) {
                String fullName = serverName + "__" + tool.name();
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
                toolRegistry().registerDynamic(fullName, definition, new McpToolHandler(serverName, tool.name()));
            }
            // Update state atomically (Finding 8.3: synchronize replacement to prevent
            // in-flight tool calls from referencing stale tool definitions)
            clientsLock.lock();
            try {
                clients.put(serverName, new McpServerState(state.properties(), state.client(), freshTools));
            } finally {
                clientsLock.unlock();
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

    private void registerTools(String serverName, List<McpSchema.Tool> tools) {
        for (McpSchema.Tool tool : tools) {
            String fullName = serverName + "__" + tool.name();
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
                    .anyMatch(td -> td.name().equals(fullName) || td.name().endsWith("__" + tool.name()))) {
                log.warn("MCP tool name collision: '{}' from server '{}' collides with an existing tool. " +
                    "Preferring server-native tool.", tool.name(), serverName);
            }
            ToolDefinition definition = convertToolDefinition(fullName, tool);
            toolRegistry().registerDynamic(fullName, definition, new McpToolHandler(serverName, tool.name()));
        }
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
                result.add(new DiscoveredTool(serverName, tool.name(), convertToolDefinition(serverName + "__" + tool.name(), tool)));
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
            var result = state.client().readResource(new McpSchema.ReadResourceRequest(uri));
            return result.contents().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("MCP read resource failed: " + sanitizeError(e.getMessage()), e);
        }
    }

    public McpSchema.CallToolResult executeTool(String serverName, String toolName, String argumentsJson) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            return state.client().callTool(new McpSchema.CallToolRequest(toolName, args));
        } catch (Exception e) {
            throw new RuntimeException("MCP tool call failed: " + sanitizeError(e.getMessage()), e);
        }
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
        reconnectExecutor = newReconnectExecutor();
        toolRefreshExecutor = newToolRefreshExecutor();
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
    }

    static ToolDefinition convertToolDefinition(String fullName, McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (schema.containsKey("properties") && schema.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                Object raw = entry.getValue();
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    properties.put(String.valueOf(entry.getKey()), copy);
                } else {
                    properties.put(String.valueOf(entry.getKey()), raw);
                }
            }
        }
        if (schema.containsKey("required") && schema.get("required") instanceof List<?> req) {
            for (Object r : req) {
                required.add(String.valueOf(r));
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        return new ToolDefinition(fullName, tool.description(), parameters);
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
            // ── Rate limiting ──
            if (rateLimiter != null) {
                String rateLimitKey = serverName + "__" + toolName;
                if (!rateLimiter.tryAcquire(rateLimitKey,
                        properties.getMcp().getRateLimitMaxCalls() > 0 ? properties.getMcp().getRateLimitMaxCalls() : 0,
                        properties.getMcp().getRateLimitWindowSeconds() > 0 ? properties.getMcp().getRateLimitWindowSeconds() : 0)) {
                    return ToolResult.fail("Rate limit exceeded for MCP tool: " + toolName);
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
                        return ToolResult.fail("Tool call blocked by security scanner: " + argScan.getThreatDescription());
                    }
                } catch (Exception e) {
                    // If arguments can't be parsed as JSON, proceed — let the tool handler deal with it
                    log.debug("Could not parse tool arguments for scanning: {}", e.getMessage());
                }
            }
            try {
                var result = executeTool(serverName, toolName, arguments);
                String text = result.content().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
                // H-SYNC: Strip invisible Unicode TAG characters from MCP tool output
                text = com.azhukov.agent.core.security.UnicodeTagStripper.stripUnicodeTags(text);
                // ── Response security scan ──
                String safeText = text;
                if (responseScanner != null) {
                    ScanResult responseScan = responseScanner.scan(text);
                    if (!responseScan.isClean()) {
                        log.warn("MCP tool '{}' response had security findings: {}",
                            toolName, responseScan.getThreatDescription());
                    }
                    // Use sanitized text if available
                    safeText = responseScan.getSanitizedText() != null ? responseScan.getSanitizedText() : text;
                }
                return ToolResult.ok(safeText);
            } catch (Exception e) {
                // Only reconnect on actual connection failures, not tool execution errors.
                // Tool execution errors (e.g. bad arguments, server-side logic errors) should
                // NOT trigger a reconnect — that would unnecessarily tear down a working connection.
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isConnectionError = msg.contains("connection") || msg.contains("closed")
                    || msg.contains("disconnected") || msg.contains("refused")
                    || msg.contains("reset") || msg.contains("not connected");
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
                return ToolResult.fail("MCP tool failed: " + sanitizeError(e.getMessage()));
            }
        }
    }
}