package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
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
import java.util.concurrent.TimeUnit;
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
    private final Map<String, McpServerState> clients = new ConcurrentHashMap<>();

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

    private final ScheduledExecutorService reconnectExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "mcp-reconnect");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService toolRefreshExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "mcp-tool-refresh");
        t.setDaemon(true);
        return t;
    });

    private ToolRegistry toolRegistry() {
        return applicationContext.getBean(ToolRegistry.class);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        connectConfiguredServers();
    }

    public void connectConfiguredServers() {
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP is disabled.");
            return;
        }
        for (AgentProperties.McpProperties.ServerProperties server : properties.getMcp().getServers()) {
            connect(server);
        }
    }

    public void connect(AgentProperties.McpProperties.ServerProperties server) {
        if (clients.containsKey(server.getName())) {
            return;
        }
        try {
            McpSyncClient client = createClient(server);
            client.initialize();
            var tools = client.listTools().tools();
            clients.put(server.getName(), new McpServerState(server, client, tools));
            registerTools(server.getName(), tools);
            scheduleToolRefresh(server.getName());
            log.info("Connected to MCP server {} ({}) with {} tools", server.getName(), server.getTransport(), tools.size());
        } catch (Exception e) {
            log.warn("Failed to connect to MCP server {}: {}", server.getName(), e.getMessage());
            scheduleReconnect(server, 0, true);
        }
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
        // Check for shell metacharacters
        for (String metachar : SHELL_METACHARACTERS) {
            if (trimmed.contains(metachar)) {
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
                clients.put(server.getName(), new McpServerState(server, client, tools));
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
            } catch (Exception ignored) {
            }
        }
        scheduleReconnect(serverProps, 0, false);
    }

    // ── Dynamic tool refresh ─────────────────────────────────────────────
    private void scheduleToolRefresh(String serverName) {
        toolRefreshExecutor.scheduleWithFixedDelay(() -> {
            try {
                refreshTools(serverName);
            } catch (Exception e) {
                log.debug("Tool refresh for MCP server {} failed: {}", serverName, e.getMessage());
            }
        }, TOOL_REFRESH_INTERVAL_SECONDS, TOOL_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void refreshTools(String serverName) {
        var state = clients.get(serverName);
        if (state == null) {
            return;
        }
        try {
            var freshTools = state.client().listTools().tools();
            // Detect changes
            Set<String> oldNames = state.tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            Set<String> newNames = freshTools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
            if (oldNames.equals(newNames)) {
                return; // No changes detected
            }
            log.info("MCP server {} tool list changed: {} -> {} tools", serverName, oldNames.size(), newNames.size());
            // Deregister stale tools
            for (String oldName : oldNames) {
                if (!newNames.contains(oldName)) {
                    String fullName = serverName + "__" + oldName;
                    toolRegistry().deregisterDynamic(fullName);
                    log.info("Deregistered stale MCP tool: {}", fullName);
                }
            }
            // Register new/updated tools
            for (McpSchema.Tool tool : freshTools) {
                String fullName = serverName + "__" + tool.name();
                ToolDefinition definition = convertToolDefinition(fullName, tool);
                toolRegistry().registerDynamic(fullName, definition, new McpToolHandler(serverName, tool.name()));
            }
            // Update state in-place
            clients.put(serverName, new McpServerState(state.properties(), state.client(), freshTools));
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
        }
    }

    private void registerTools(String serverName, List<McpSchema.Tool> tools) {
        for (McpSchema.Tool tool : tools) {
            String fullName = serverName + "__" + tool.name();
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
        shutdownRequested.set(true);
        reconnectExecutor.shutdownNow();
        toolRefreshExecutor.shutdownNow();
        for (var state : clients.values()) {
            try {
                state.client().close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
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
            try {
                var result = executeTool(serverName, toolName, arguments);
                String text = result.content().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
                return ToolResult.ok(text);
            } catch (Exception e) {
                // Check if this is a connection error — trigger auto-reconnect
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("connection") || msg.contains("closed") || msg.contains("disconnected")
                    || msg.contains("refused") || msg.contains("reset") || msg.contains("not connected")
                    || e instanceof java.io.IOException) {
                    log.warn("MCP tool '{}' on server '{}' failed with connection error, triggering reconnect: {}",
                        toolName, serverName, e.getMessage());
                    // Find the server properties and schedule a reconnect
                    properties.getMcp().getServers().stream()
                        .filter(s -> s.getName().equals(serverName))
                        .findFirst()
                        .ifPresent(server -> {
                            // Remove stale client entry
                            clients.remove(serverName);
                            scheduleReconnect(server, 0, false);
                        });
                }
                return ToolResult.fail("MCP tool failed: " + sanitizeError(e.getMessage()));
            }
        }
    }
}