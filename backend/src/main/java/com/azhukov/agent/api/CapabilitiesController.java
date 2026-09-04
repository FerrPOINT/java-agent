package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.RuntimeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /v1/capabilities — machine-readable API capabilities.
 *
 * Mirrors Hermes' GET /v1/capabilities endpoint: advertises the stable API
 * surface so external UIs and orchestrators can discover what the agent
 * supports without scraping docs.
 */
@RestController
@RequestMapping({"/v1/capabilities", "/p/{profile}/v1/capabilities"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "OpenAI-compatible", description = "Agent capabilities discovery")
public class CapabilitiesController {

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final RuntimeConfigService runtimeConfigService;

    private static final List<String> BROWSER_CONTROL_CAPABILITIES = List.of(
        "browser_back",
        "browser_click",
        "browser_navigate",
        "browser_press",
        "browser_screenshot",
        "browser_scroll",
        "browser_snapshot",
        "browser_tab_activate",
        "browser_tabs",
        "browser_type",
        "controller.noop"
    );
    private static final List<String> BROWSER_CONTROL_ARTIFACT_CAPABILITIES = List.of(
        "browser_artifact_download",
        "browser_artifact_upload"
    );
    private static final List<String> BROWSER_CONTROL_DEVELOPER_CAPABILITIES = List.of(
        "browser_cdp",
        "browser_evaluate"
    );
    private static final List<String> BROWSER_CONTROL_ALLOWED_MIME_TYPES = List.of(
        "application/json",
        "application/pdf",
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/webp",
        "text/plain"
    );

    @GetMapping
    @Operation(summary = "List agent capabilities, features, and available endpoints")
    public Map<String, Object> capabilities() {
        String model = resolveModel();

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("chat_completions", true);
        features.put("chat_completions_streaming", true);
        features.put("responses_api", true);
        features.put("responses_streaming", true);
        features.put("run_submission", true);
        features.put("run_status", true);
        features.put("run_events_sse", true);
        features.put("run_stop", true);
        features.put("run_steer", true);
        features.put("run_approval_response", true);
        features.put("tool_progress_events", true);
        features.put("approval_events", true);
        features.put("session_resources", true);
        features.put("model_options", true);
        features.put("session_chat", true);
        features.put("session_chat_streaming", true);
        features.put("session_fork", true);
        features.put("session_model_lock", true);
        features.put("admin_config_rw", false);      // Admin config RW — deferred, not yet implemented
        features.put("jobs_admin", false);
        features.put("memory_write_api", false);      // Stable Hermes-compatible memory-write API is not exposed
        features.put("skills_api", true);
        features.put("audio_api", false);
        features.put("realtime_voice", false);       // Realtime voice — deferred, not yet implemented
        features.put("session_continuity_header", "X-Hermes-Session-Id");
        features.put("session_key_header", "X-Hermes-Session-Key");
        features.put("cors", hasConfiguredCorsOrigins());
        features.put("browser_extension_control", browserExtensionControl());

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("health", Map.of("method", "GET", "path", "/health"));
        endpoints.put("health_detailed", Map.of("method", "GET", "path", "/health/detailed"));
        endpoints.put("models", Map.of("method", "GET", "path", "/v1/models"));
        endpoints.put("model_options", Map.of("method", "GET", "path", "/api/model/options"));
        endpoints.put("capabilities", Map.of("method", "GET", "path", "/v1/capabilities"));
        endpoints.put("toolsets", Map.of("method", "GET", "path", "/v1/toolsets"));
        endpoints.put("chat_completions", Map.of("method", "POST", "path", "/v1/chat/completions"));
        endpoints.put("responses", Map.of("method", "POST", "path", "/v1/responses"));
        endpoints.put("runs", Map.of("method", "POST", "path", "/v1/runs"));
        endpoints.put("run_status", Map.of("method", "GET", "path", "/v1/runs/{run_id}"));
        endpoints.put("run_events", Map.of("method", "GET", "path", "/v1/runs/{run_id}/events"));
        endpoints.put("run_approval", Map.of("method", "POST", "path", "/v1/runs/{run_id}/approval"));
        endpoints.put("run_steer", Map.of("method", "POST", "path", "/v1/runs/{run_id}/steer"));
        endpoints.put("run_stop", Map.of("method", "POST", "path", "/v1/runs/{run_id}/stop"));
        endpoints.put("sessions", Map.of("method", "GET", "path", "/api/sessions"));
        endpoints.put("session_create", Map.of("method", "POST", "path", "/api/sessions"));
        endpoints.put("session", Map.of("method", "GET", "path", "/api/sessions/{session_id}"));
        endpoints.put("session_update", Map.of("method", "PATCH", "path", "/api/sessions/{session_id}"));
        endpoints.put("session_delete", Map.of("method", "DELETE", "path", "/api/sessions/{session_id}"));
        endpoints.put("session_messages", Map.of("method", "GET", "path", "/api/sessions/{session_id}/messages"));
        endpoints.put("session_fork", Map.of("method", "POST", "path", "/api/sessions/{session_id}/fork"));
        endpoints.put("session_chat", Map.of("method", "POST", "path", "/api/sessions/{session_id}/chat"));
        endpoints.put("session_chat_stream", Map.of("method", "POST", "path", "/api/sessions/{session_id}/chat/stream"));
        endpoints.put("session_model_lock", Map.of("method", "POST", "path", "/api/sessions/{session_id}/model"));
        endpoints.put("browser_control_register", Map.of("method", "POST", "path", "/v1/browser-control/register"));
        endpoints.put("browser_control_ws", Map.of("method", "GET", "path", "/v1/browser-control/ws"));
        endpoints.put("artifact_upload", Map.of("method", "POST", "path", "/v1/artifacts/upload"));
        endpoints.put("artifact_download", Map.of("method", "GET", "path", "/v1/artifacts/download/{artifact_id}"));
        endpoints.put("skills", Map.of("method", "GET", "path", "/v1/skills"));

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", "bearer");
        auth.put("required", properties.getSecurity() != null
            && properties.getSecurity().getApiKey() != null
            && !properties.getSecurity().getApiKey().isBlank());

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("mode", "server_agent");
        runtime.put("tool_execution", "server");
        runtime.put("split_runtime", false);
        runtime.put(
            "description",
            "The API server creates a server-side Hermes-compatible agent; "
                + "tools execute on the API-server host unless a future explicit split-runtime mode is enabled.");

        return Map.of(
            "object", "java-agent.api_server.capabilities",
            "platform", "java-agent",
            "model", model,
            "auth", auth,
            "runtime", runtime,
            "features", features,
            "endpoints", endpoints,
            "toolsets", toolRegistry.getToolsets(),
            "skills_count", skillManager.listSkillNames().size(),
            "tools_count", toolRegistry.getDefinitions().size()
        );
    }

    private Map<String, Object> browserExtensionControl() {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("enabled", false);
        control.put("protocol_version", 1);
        control.put("capabilities", BROWSER_CONTROL_CAPABILITIES);
        control.put("artifact_capabilities", BROWSER_CONTROL_ARTIFACT_CAPABILITIES);
        control.put("developer_capabilities", BROWSER_CONTROL_DEVELOPER_CAPABILITIES);
        control.put("developer_mode", false);
        Map<String, Object> artifactTransport = new LinkedHashMap<>();
        artifactTransport.put("upload", Map.of("method", "POST", "path", "/v1/artifacts/upload"));
        artifactTransport.put("download", Map.of("method", "GET", "path", "/v1/artifacts/download/{artifact_id}"));
        artifactTransport.put("max_bytes", 10 * 1024 * 1024);
        artifactTransport.put("ttl_seconds", 300.0);
        artifactTransport.put("allowed_mime_types", BROWSER_CONTROL_ALLOWED_MIME_TYPES);
        control.put("artifact_transport", artifactTransport);
        control.put("real_browser_actions", true);
        control.put("transports", Map.of(
            "local_vps", "websocket-subprotocol-ticket",
            "cloud", "authenticated-gateway-rpc"
        ));
        return control;
    }

    private String resolveModel() {
        return OpenAiModelRouting.advertisedModel(properties);
    }

    private boolean hasConfiguredCorsOrigins() {
        AgentProperties.ApiProperties api = properties.getApi();
        return api != null
            && api.getCorsOrigins() != null
            && api.getCorsOrigins().stream().anyMatch(origin -> origin != null && !origin.isBlank());
    }
}
