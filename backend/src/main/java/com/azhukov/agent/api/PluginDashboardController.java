package com.azhukov.agent.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe fallback for Hermes desktop plugin REST namespaces.
 *
 * <p>Hermes mounts plugin-owned FastAPI routers under
 * {@code /api/plugins/<plugin>/...}. The Java port has no equivalent runtime,
 * so every plugin namespace request returns an explicit unsupported response
 * instead of leaking through to the generic 404 handler.</p>
 */
@RestController
@Tag(name = "Hermes-compatible", description = "Plugin dashboard compatibility")
public class PluginDashboardController {

    private static final String DETAIL = "plugin API routes are not implemented in the Java port";
    private static final String AGENT_PLUGIN_DETAIL = "agent plugin management is not implemented in the Java port";
    private static final String PLUGIN_VISIBILITY_DETAIL = "dashboard plugin visibility is not implemented in the Java port";

    @GetMapping("/api/dashboard/plugins")
    @Operation(summary = "Return empty dashboard plugin catalog for the Java port")
    public List<Map<String, Object>> dashboardPlugins() {
        return List.of();
    }

    @GetMapping("/api/dashboard/plugins/rescan")
    @Operation(summary = "Return dashboard plugin rescan fallback")
    public Map<String, Object> rescanDashboardPlugins() {
        return Map.of("ok", true, "count", 0);
    }

    @GetMapping("/api/dashboard/plugins/hub")
    @Operation(summary = "Return empty plugin hub with Java provider fallbacks")
    public Map<String, Object> dashboardPluginsHub() {
        Map<String, Object> memoryProvider = new LinkedHashMap<>();
        memoryProvider.put("name", "builtin");
        memoryProvider.put("description", "Java agent built-in memory provider");
        memoryProvider.put("available", true);
        memoryProvider.put("configured", true);
        memoryProvider.put("status", "ready");
        memoryProvider.put("setup", Map.of(
            "dependencies_installed", true,
            "pip_dependencies", List.of(),
            "external_dependencies", List.of(),
            "required_env", List.of()));

        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("memory_provider", "builtin");
        providers.put("memory_options", List.of(memoryProvider));
        providers.put("context_engine", "compressor");
        providers.put("context_options", List.of(Map.of(
            "name", "compressor",
            "description", "Java agent built-in context compressor")));

        return Map.of(
            "plugins", List.of(),
            "orphan_dashboard_plugins", List.of(),
            "providers", providers);
    }

    @PostMapping("/api/dashboard/agent-plugins/install")
    @Operation(summary = "Reject dashboard agent plugin installation not supported by Java port")
    public ResponseEntity<Map<String, Object>> installAgentPlugin(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String identifier = body != null ? stringOrNull(body.get("identifier")) : null;
        return agentPluginUnsupported("install", identifier);
    }

    @PostMapping("/api/dashboard/agent-plugins/**")
    @Operation(summary = "Reject dashboard agent plugin mutation not supported by Java port")
    public ResponseEntity<Map<String, Object>> mutateAgentPlugin(HttpServletRequest request) {
        String route = pathAfter(request.getRequestURI(), "/api/dashboard/agent-plugins/");
        String action = null;
        String rawName = null;
        if (route.endsWith("/enable")) {
            action = "enable";
            rawName = route.substring(0, route.length() - "/enable".length());
        } else if (route.endsWith("/disable")) {
            action = "disable";
            rawName = route.substring(0, route.length() - "/disable".length());
        } else if (route.endsWith("/update")) {
            action = "update";
            rawName = route.substring(0, route.length() - "/update".length());
        }
        if (action == null) {
            return error(HttpStatus.BAD_REQUEST, "Unknown agent plugin action");
        }

        String name = cleanPluginPathName(rawName);
        if (name == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin name");
        }
        return agentPluginUnsupported(action, name);
    }

    @DeleteMapping("/api/dashboard/agent-plugins/**")
    @Operation(summary = "Reject dashboard agent plugin removal not supported by Java port")
    public ResponseEntity<Map<String, Object>> removeAgentPlugin(HttpServletRequest request) {
        String name = cleanPluginPathName(pathAfter(request.getRequestURI(), "/api/dashboard/agent-plugins/"));
        if (name == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin name");
        }
        return agentPluginUnsupported("remove", name);
    }

    @PostMapping("/api/dashboard/plugins/**")
    @Operation(summary = "Reject dashboard plugin visibility persistence not supported by Java port")
    public ResponseEntity<Map<String, Object>> setPluginVisibility(
        HttpServletRequest request,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String route = pathAfter(request.getRequestURI(), "/api/dashboard/plugins/");
        if (!route.endsWith("/visibility")) {
            return error(HttpStatus.BAD_REQUEST, "Unknown dashboard plugin action");
        }
        String name = cleanPluginPathName(route.substring(0, route.length() - "/visibility".length()));
        if (name == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin name");
        }

        Map<String, Object> response = new LinkedHashMap<>(errorBody(PLUGIN_VISIBILITY_DETAIL));
        response.put("ok", false);
        response.put("name", name);
        response.put("hidden", body != null && Boolean.TRUE.equals(body.get("hidden")));
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }

    @GetMapping("/dashboard-plugins/{pluginId}/**")
    @Operation(summary = "Reject dashboard plugin static assets not supported by Java port")
    public ResponseEntity<Map<String, Object>> dashboardPluginAsset(
        @PathVariable String pluginId,
        HttpServletRequest request
    ) {
        String normalized = cleanPluginId(pluginId);
        if (normalized == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin id");
        }

        String suffix = pathAfter(request.getRequestURI(), "/dashboard-plugins/" + pluginId);
        if (containsTraversal(suffix)) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin path");
        }
        return error(HttpStatus.NOT_FOUND, "Plugin not found");
    }

    @RequestMapping(
        value = {"/api/plugins/{pluginId}", "/api/plugins/{pluginId}/**"},
        method = {
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE
        }
    )
    @Operation(summary = "Reject plugin-owned dashboard API routes not supported by Java port")
    public ResponseEntity<Map<String, Object>> pluginApi(
        @PathVariable String pluginId,
        HttpServletRequest request
    ) {
        String normalized = cleanPluginId(pluginId);
        if (normalized == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin id");
        }

        String suffix = pluginSuffix(request.getRequestURI(), normalized);
        if (containsTraversal(suffix)) {
            return error(HttpStatus.BAD_REQUEST, "Invalid plugin path");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("detail", DETAIL);
        response.put("error", DETAIL);
        response.put("plugin", normalized);
        response.put("path", suffix);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(errorBody(detail));
    }

    private static ResponseEntity<Map<String, Object>> agentPluginUnsupported(String action, String name) {
        Map<String, Object> response = new LinkedHashMap<>(errorBody(AGENT_PLUGIN_DETAIL));
        response.put("ok", false);
        response.put("action", action);
        if (name != null && !name.isBlank()) {
            response.put("name", name);
        }
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }

    private static Map<String, Object> errorBody(String detail) {
        return Map.of("detail", detail, "error", detail);
    }

    private static String cleanPluginId(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        String clean = pluginId.trim();
        if (!clean.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) {
            return null;
        }
        return clean;
    }

    private static String cleanPluginPathName(String rawName) {
        if (rawName == null || rawName.isBlank() || rawName.contains("\\") || containsTraversal("/" + rawName)) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawName, StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.isBlank() || decoded.contains("\\") || containsTraversal("/" + decoded)) {
            return null;
        }
        for (String segment : decoded.split("/")) {
            if (cleanPluginId(segment) == null) {
                return null;
            }
        }
        return decoded;
    }

    private static String pluginSuffix(String requestUri, String pluginId) {
        String prefix = "/api/plugins/" + pluginId;
        String suffix = pathAfter(requestUri, prefix);
        if (suffix.isBlank()) {
            return "/";
        }
        return suffix.startsWith("/") ? suffix : "/" + suffix;
    }

    private static String pathAfter(String requestUri, String prefix) {
        if (requestUri == null || prefix == null || !requestUri.startsWith(prefix)) {
            return "";
        }
        return requestUri.substring(prefix.length());
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean containsTraversal(String suffix) {
        String decoded = suffix;
        try {
            for (int i = 0; i < 3; i++) {
                if (hasTraversalSegment(decoded)) {
                    return true;
                }
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    return false;
                }
                decoded = next;
            }
        } catch (IllegalArgumentException e) {
            return true;
        }
        return hasTraversalSegment(decoded);
    }

    private static boolean hasTraversalSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
