package com.azhukov.agent.api;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Dashboard MCP compatibility")
public class McpDashboardController {

    private static final Pattern SECRETISH_ENV = Pattern.compile(
        ".*(api[_-]?key|token|secret|password|passwd|credential|auth).*",
        Pattern.CASE_INSENSITIVE
    );

    private final McpLifecycleManager mcpLifecycleManager;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    @GetMapping("/servers")
    public Map<String, Object> listServers(@RequestParam(name = "profile", required = false) String profile) {
        Map<String, McpLifecycleManager.McpServerInfo> live = liveServers();
        List<Map<String, Object>> servers = properties.getMcp().getServers().stream()
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(server -> serverSummary(server, live.get(server.getName())))
            .collect(Collectors.toList());

        for (McpLifecycleManager.McpServerInfo info : live.values()) {
            boolean configured = servers.stream()
                .anyMatch(server -> info.name().equals(server.get("name")));
            if (!configured) {
                servers.add(liveServerSummary(info));
            }
        }

        return Map.of("servers", servers);
    }

    @PostMapping("/servers/{name}/test")
    public ResponseEntity<Map<String, Object>> testServer(
        @PathVariable String name,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        AgentProperties.McpProperties.ServerProperties configured = configuredServer(name);
        if (configured == null && !liveServers().containsKey(name)) {
            return notFound("Server '" + name + "' not found");
        }

        if (configured != null && !liveServers().containsKey(name)) {
            try {
                mcpLifecycleManager.connect(configured);
            } catch (RuntimeException ignored) {
                // connect() already records/logs failures; the response below reports current live state.
            }
        }

        List<Map<String, Object>> tools = mcpLifecycleManager.listDiscoveredTools().stream()
            .filter(tool -> tool.serverName().equals(name))
            .map(this::toolSummary)
            .toList();
        if (tools.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "ok", false,
                "error", "MCP server is not connected or exposes no tools",
                "tools", List.of()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "tools", tools,
            "prompts", 0,
            "resources", 0
        ));
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@RequestParam(name = "profile", required = false) String profile) {
        return Map.of(
            "entries", List.of(),
            "diagnostics", List.of(Map.of(
                "name", "java-agent",
                "kind", "unsupported",
                "message", "MCP catalog installation is not implemented in Java agent"
            ))
        );
    }

    @PostMapping("/servers")
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody(required = false) Map<String, Object> body) {
        return notImplemented("MCP server config writes are not implemented in Java agent");
    }

    @PutMapping("/servers")
    public ResponseEntity<Map<String, Object>> replaceServers(@RequestBody(required = false) Map<String, Object> body) {
        return notImplemented("MCP server config replacement is not implemented in Java agent");
    }

    @DeleteMapping("/servers/{name}")
    public ResponseEntity<Map<String, Object>> removeServer(@PathVariable String name) {
        return notImplemented("MCP server removal is not implemented in Java agent");
    }

    @PutMapping("/servers/{name}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("Per-server MCP enable toggles are not implemented in Java agent");
    }

    @PostMapping("/servers/{name}/auth")
    public ResponseEntity<Map<String, Object>> authServer(@PathVariable String name) {
        return notImplemented("Dashboard MCP OAuth is not implemented in Java agent");
    }

    @GetMapping("/oauth/flows/{flowId}")
    public ResponseEntity<Map<String, Object>> oauthFlow(@PathVariable String flowId) {
        return notFound("OAuth flow not found or expired");
    }

    @DeleteMapping("/oauth/flows/{flowId}")
    public Map<String, Object> cancelOAuthFlow(@PathVariable String flowId) {
        return Map.of("ok", true, "status", "expired");
    }

    @GetMapping(value = "/oauth/callback/{*serverName}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(
        @PathVariable String serverName,
        @RequestParam(name = "code", required = false) String code,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "error", required = false) String error
    ) {
        return ResponseEntity.status(HttpStatusCode.valueOf(404))
            .contentType(MediaType.TEXT_HTML)
            .body("<h1>OAuth flow expired</h1><p>Return to Hermes and try again.</p>");
    }

    @PostMapping("/catalog/install")
    public ResponseEntity<Map<String, Object>> installCatalogEntry(@RequestBody(required = false) Map<String, Object> body) {
        return notImplemented("MCP catalog installation is not implemented in Java agent");
    }

    private Map<String, Object> serverSummary(
        AgentProperties.McpProperties.ServerProperties server,
        McpLifecycleManager.McpServerInfo live
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", server.getName());
        body.put("transport", transport(server));
        body.put("url", blankToNull(server.getBaseUrl()));
        body.put("command", blankToNull(server.getCommand()));
        body.put("args", List.copyOf(server.getArgs()));
        body.put("env", redactedEnv(server.getEnv()));
        body.put("auth", authMode(server));
        body.put("enabled", server.isEnabled());
        body.put("tools", live != null ? live.toolNames() : null);
        return body;
    }

    private Map<String, Object> liveServerSummary(McpLifecycleManager.McpServerInfo info) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", info.name());
        body.put("transport", blankToDefault(info.transport(), "unknown"));
        body.put("url", blankToNull(info.baseUrl()));
        body.put("command", null);
        body.put("args", List.of());
        body.put("env", Map.of());
        body.put("auth", null);
        body.put("enabled", true);
        body.put("tools", info.toolNames());
        return body;
    }

    private Map<String, Object> toolSummary(McpLifecycleManager.DiscoveredTool tool) {
        ToolDefinition definition = tool.definition();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tool.toolName());
        body.put("description", definition != null ? definition.description() : "");
        if (definition != null && definition.parameters() != null) {
            try {
                body.put("schema_chars", objectMapper.writeValueAsString(definition.parameters()).length());
            } catch (Exception ignored) {
                // schema_chars is additive; skip it if serialization fails.
            }
        }
        return body;
    }

    private Map<String, McpLifecycleManager.McpServerInfo> liveServers() {
        return mcpLifecycleManager.listServers().stream()
            .collect(Collectors.toMap(
                McpLifecycleManager.McpServerInfo::name,
                info -> info,
                (first, second) -> first,
                LinkedHashMap::new
            ));
    }

    private AgentProperties.McpProperties.ServerProperties configuredServer(String name) {
        return properties.getMcp().getServers().stream()
            .filter(server -> server.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    private static String transport(AgentProperties.McpProperties.ServerProperties server) {
        if (server.getBaseUrl() != null && !server.getBaseUrl().isBlank()) {
            return "http";
        }
        if (server.getCommand() != null && !server.getCommand().isBlank()) {
            return "stdio";
        }
        return blankToDefault(server.getTransport(), "unknown").toLowerCase(Locale.ROOT);
    }

    private static String authMode(AgentProperties.McpProperties.ServerProperties server) {
        boolean hasAuthorizationHeader = server.getHeaders().keySet().stream()
            .anyMatch(key -> "authorization".equalsIgnoreCase(key));
        if (hasAuthorizationHeader) {
            return "header";
        }
        if ((server.getOauthTokenUrl() != null && !server.getOauthTokenUrl().isBlank())
            || (server.getOauthClientId() != null && !server.getOauthClientId().isBlank())) {
            return "oauth";
        }
        return null;
    }

    private static Map<String, String> redactedEnv(Map<String, String> env) {
        Map<String, String> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            redacted.put(key, SECRETISH_ENV.matcher(key).matches() && value != null && !value.isBlank()
                ? "[REDACTED]"
                : value);
        }
        return redacted;
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatusCode.valueOf(404)).body(errorBody(detail));
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatusCode.valueOf(501)).body(errorBody(detail));
    }

    private static Map<String, Object> errorBody(String detail) {
        return Map.of("detail", detail, "error", detail);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
