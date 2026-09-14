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
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.McpConfigStore> configStoreProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.McpOAuthFlowService> oauthFlowProvider;

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

    /**
     * Catalog is DATA ONLY (docs/35 WP-3 item 5): reviewed, built-in server
     * definitions. Installing creates a persisted config row via the config
     * store — no arbitrary code or URL download happens.
     */
    private static final List<Map<String, Object>> CATALOG = List.of(
        Map.of("name", "filesystem", "transport", "stdio", "command", "npx",
            "args", List.of("-y", "@modelcontextprotocol/server-filesystem", "."),
            "description", "Restricted filesystem access for the working directory"),
        Map.of("name", "memory", "transport", "stdio", "command", "npx",
            "args", List.of("-y", "@modelcontextprotocol/server-memory"),
            "description", "Persistent memory graph server"),
        Map.of("name", "everything", "transport", "stdio", "command", "npx",
            "args", List.of("-y", "@modelcontextprotocol/server-everything"),
            "description", "Test server exposing tools/resources/prompts"));

    @GetMapping("/catalog")
    @io.swagger.v3.oas.annotations.Operation(summary = "Reviewed catalog entries (data only; install creates a persisted config)")
    public Map<String, Object> catalog(@RequestParam(name = "profile", required = false) String profile) {
        return Map.of("entries", CATALOG);
    }

    @PostMapping("/servers")
    @io.swagger.v3.oas.annotations.Operation(summary = "Add/update a persisted MCP server config (validated, revisioned)")
    public ResponseEntity<Map<String, Object>> addServer(
        @RequestParam(name = "profile", required = false) String profile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        com.azhukov.agent.service.McpConfigStore store = configStore();
        if (store == null) {
            return notImplemented("MCP config store is not available in this deployment");
        }
        try {
            com.azhukov.agent.service.McpConfigStore.ServerConfigInput input = parseServerInput(body);
            var saved = store.upsert(profile, input);
            return ResponseEntity.ok(store.redactedView(saved));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/servers")
    @io.swagger.v3.oas.annotations.Operation(summary = "Replace persisted MCP server configs from a servers list")
    public ResponseEntity<Map<String, Object>> replaceServers(
        @RequestParam(name = "profile", required = false) String profile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Object rawServers = body == null ? null : body.get("servers");
        if (!(rawServers instanceof List<?> entries) || entries.isEmpty()) {
            return badRequest("servers list is required");
        }
        com.azhukov.agent.service.McpConfigStore store = configStore();
        if (store == null) {
            return notImplemented("MCP config store is not available in this deployment");
        }
        List<Map<String, Object>> saved = new java.util.ArrayList<>();
        try {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> entryMap)) {
                    throw new IllegalArgumentException("each server entry must be a mapping");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entryBody = (Map<String, Object>) entryMap;
                saved.add(store.redactedView(store.upsert(profile, parseServerInput(entryBody))));
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        return ResponseEntity.ok(Map.of("servers", saved));
    }

    @DeleteMapping("/servers/{name}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Delete a persisted MCP server config and its schema cache")
    public ResponseEntity<Map<String, Object>> removeServer(
        @PathVariable String name,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        com.azhukov.agent.service.McpConfigStore store = configStore();
        if (store == null) {
            return notImplemented("MCP config store is not available in this deployment");
        }
        boolean deleted = store.delete(profile, name);
        return deleted
            ? ResponseEntity.ok(Map.of("ok", true, "deleted", name))
            : notFound("Server '" + name + "' not found");
    }

    @PutMapping("/servers/{name}/enabled")
    @io.swagger.v3.oas.annotations.Operation(summary = "Toggle a persisted MCP server enabled flag (bumps config revision)")
    public ResponseEntity<Map<String, Object>> setEnabled(
        @PathVariable String name,
        @RequestParam(name = "profile", required = false) String profile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        com.azhukov.agent.service.McpConfigStore store = configStore();
        if (store == null) {
            return notImplemented("MCP config store is not available in this deployment");
        }
        boolean enabled = body == null
            || body.get("enabled") == null
            || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return store.setEnabled(profile, name, enabled)
            .<ResponseEntity<Map<String, Object>>>map(saved ->
                ResponseEntity.ok(store.redactedView(saved)))
            .orElseGet(() -> notFound("Server '" + name + "' not found"));
    }

    @PostMapping("/servers/{name}/auth")
    @io.swagger.v3.oas.annotations.Operation(summary = "Start an OAuth Authorization Code + PKCE flow for a configured server")
    public ResponseEntity<Map<String, Object>> authServer(
        @PathVariable String name,
        @RequestParam(name = "profile", required = false) String profile,
        @RequestParam(name = "redirect_base", required = false) String redirectBase,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        com.azhukov.agent.service.McpOAuthFlowService flows = oauthFlows();
        if (flows == null) {
            return notImplemented("MCP OAuth flows are not available in this deployment");
        }
        String base = redirectBase != null ? redirectBase
            : (body == null ? null : str(body.get("redirect_base")));
        try {
            var started = flows.start(profile, name, base);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("flow_id", started.flowId());
            response.put("authorization_url", started.authorizationUrl());
            response.put("expires_in", started.expiresIn());
            response.put("status", "pending");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/oauth/flows/{flowId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "OAuth flow status (no token values are ever returned)")
    public ResponseEntity<Map<String, Object>> oauthFlow(@PathVariable String flowId) {
        com.azhukov.agent.service.McpOAuthFlowService flows = oauthFlows();
        if (flows == null) {
            return notFound("OAuth flow not found or expired");
        }
        return flows.status(flowId)
            .<ResponseEntity<Map<String, Object>>>map(flow -> ResponseEntity.ok(Map.of(
                "flow_id", flowId,
                "server", flow.getServerName(),
                "status", flow.getStatus(),
                "expires_at", flow.getExpiresAt().toString())))
            .orElseGet(() -> notFound("OAuth flow not found or expired"));
    }

    @DeleteMapping("/oauth/flows/{flowId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Cancel a pending OAuth flow")
    public Map<String, Object> cancelOAuthFlow(@PathVariable String flowId) {
        com.azhukov.agent.service.McpOAuthFlowService flows = oauthFlows();
        boolean cancelled = flows != null && flows.cancel(flowId);
        return Map.of("ok", true, "status", cancelled ? "cancelled" : "expired");
    }

    @GetMapping(value = "/oauth/callback/{*serverName}", produces = MediaType.TEXT_HTML_VALUE)
    @io.swagger.v3.oas.annotations.Operation(summary = "OAuth callback: validate state, exchange code with PKCE verifier, store encrypted token")
    public ResponseEntity<String> oauthCallback(
        @PathVariable String serverName,
        @RequestParam(name = "code", required = false) String code,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "error", required = false) String error
    ) {
        com.azhukov.agent.service.McpOAuthFlowService flows = oauthFlows();
        if (flows == null) {
            return html(ResponseEntity.status(HttpStatusCode.valueOf(404)),
                "<h1>OAuth unavailable</h1><p>OAuth flows are not available in this deployment.</p>");
        }
        String cleanName = serverName.startsWith("/") ? serverName.substring(1) : serverName;
        if (error != null && !error.isBlank()) {
            return html(ResponseEntity.ok(),
                "<h1>Authorization failed</h1><p>" + escapeHtml(error) + "</p>");
        }
        Map<String, Object> result = flows.complete(cleanName, code, state);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        String detail = String.valueOf(result.getOrDefault("detail", result.get("status")));
        return html(ResponseEntity.ok(),
            ok
                ? "<h1>Authorization complete</h1><p>You can return to the dashboard.</p>"
                : "<h1>Authorization failed</h1><p>" + escapeHtml(detail) + "</p>");
    }

    private static ResponseEntity<String> html(ResponseEntity.BodyBuilder builder, String body) {
        return builder.contentType(MediaType.TEXT_HTML).body(body);
    }

    private static String escapeHtml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    @PostMapping("/catalog/install")
    @io.swagger.v3.oas.annotations.Operation(summary = "Install a reviewed catalog entry as a persisted server config")
    public ResponseEntity<Map<String, Object>> installCatalogEntry(
        @RequestParam(name = "profile", required = false) String profile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String name = body == null ? null : str(body.get("name"));
        if (name == null || name.isBlank()) {
            return badRequest("catalog entry name is required");
        }
        Map<String, Object> entry = CATALOG.stream()
            .filter(candidate -> name.equals(candidate.get("name")))
            .findFirst().orElse(null);
        if (entry == null) {
            return notFound("Catalog entry '" + name + "' not found");
        }
        com.azhukov.agent.service.McpConfigStore store = configStore();
        if (store == null) {
            return notImplemented("MCP config store is not available in this deployment");
        }
        String serverName = body.get("server_name") == null ? name : str(body.get("server_name"));
        try {
            var saved = store.upsert(profile, new com.azhukov.agent.service.McpConfigStore.ServerConfigInput(
                serverName,
                true,
                str(entry.get("transport")),
                str(entry.get("command")),
                strList(entry.get("args")),
                null, null, null, null, null,
                0, "full", null, null, null));
            return ResponseEntity.ok(Map.of("ok", true, "installed", serverName,
                "config", store.redactedView(saved)));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
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

    private com.azhukov.agent.service.McpConfigStore configStore() {
        return configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
    }

    private com.azhukov.agent.service.McpOAuthFlowService oauthFlows() {
        return oauthFlowProvider == null ? null : oauthFlowProvider.getIfAvailable();
    }

    @SuppressWarnings("unchecked")
    private static com.azhukov.agent.service.McpConfigStore.ServerConfigInput parseServerInput(Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("server config body is required");
        }
        String name = str(body.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("server name is required");
        }
        return new com.azhukov.agent.service.McpConfigStore.ServerConfigInput(
            name,
            body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))),
            body.get("transport") == null ? "stdio" : str(body.get("transport")),
            str(body.get("command")),
            strList(body.get("args")),
            str(body.get("base_url")),
            strList(body.get("env_keys")),
            strMap(body.get("headers")),
            strList(body.get("include_tools")),
            strList(body.get("exclude_tools")),
            body.get("timeout_seconds") == null ? 0
                : Double.parseDouble(String.valueOf(body.get("timeout_seconds"))),
            body.get("trust") == null ? "full" : str(body.get("trust")),
            str(body.get("oauth_token_url")),
            str(body.get("oauth_client_id")),
            str(body.get("oauth_scopes")));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<String, String> strMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(errorBody(detail));
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
