package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
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
@RequestMapping("/v1/capabilities")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "OpenAI-compatible", description = "Agent capabilities discovery")
public class CapabilitiesController {

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final RuntimeConfigService runtimeConfigService;
    // M17: Removed unused memoryProvider dependency
    private final TtsService ttsService;
    private final TranscriptionService transcriptionService;

    @GetMapping
    @Operation(summary = "List agent capabilities, features, and available endpoints")
    public Map<String, Object> capabilities() {
        String model = resolveModel();

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("chat_completions", true);
        features.put("chat_completions_streaming", true);
        features.put("responses_api", false);       // not yet implemented
        features.put("responses_streaming", false);
        features.put("run_submission", false);       // async runs not implemented
        features.put("run_status", false);
        features.put("run_events_sse", false);
        features.put("run_stop", false);
        features.put("run_approval_response", false);
        features.put("tool_progress_events", true);
        features.put("approval_events", true);
        features.put("session_resources", true);
        features.put("session_chat", true);
        features.put("session_chat_streaming", true);
        features.put("session_fork", true);
        features.put("admin_config_rw", false);
        features.put("jobs_admin", properties.getCron() != null && properties.getCron().isEnabled());
        features.put("memory_write_api", true);
        features.put("skills_api", true);
        features.put("audio_api", ttsService != null || transcriptionService != null);
        features.put("realtime_voice", false);
        features.put("session_continuity_header", "X-Session-Id");
        features.put("cors", false);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("health", Map.of("method", "GET", "path", "/api/v1/health"));
        endpoints.put("models", Map.of("method", "GET", "path", "/v1/models"));
        endpoints.put("capabilities", Map.of("method", "GET", "path", "/v1/capabilities"));
        endpoints.put("toolsets", Map.of("method", "GET", "path", "/v1/toolsets"));
        endpoints.put("chat_completions", Map.of("method", "POST", "path", "/v1/chat/completions"));
        endpoints.put("chat", Map.of("method", "POST", "path", "/api/v1/agent/chat"));
        endpoints.put("chat_stream", Map.of("method", "POST", "path", "/api/v1/agent/chat/stream"));
        endpoints.put("sessions", Map.of("method", "GET", "path", "/api/v2/sessions"));
        endpoints.put("session_create", Map.of("method", "POST", "path", "/api/v2/sessions"));
        endpoints.put("session", Map.of("method", "GET", "path", "/api/v2/sessions/{sessionId}"));
        endpoints.put("session_update", Map.of("method", "PATCH", "path", "/api/v2/sessions/{sessionId}"));
        endpoints.put("session_delete", Map.of("method", "DELETE", "path", "/api/v2/sessions/{sessionId}"));
        endpoints.put("session_messages", Map.of("method", "GET", "path", "/api/v2/sessions/{sessionId}/messages"));
        endpoints.put("session_chat", Map.of("method", "POST", "path", "/api/v2/sessions/{sessionId}/chat"));
        endpoints.put("session_chat_stream", Map.of("method", "POST", "path", "/api/v2/sessions/{sessionId}/chat/stream"));
        endpoints.put("session_fork", Map.of("method", "POST", "path", "/api/v1/agent/session/{sessionId}/branch"));
        endpoints.put("skills", Map.of("method", "GET", "path", "/api/v1/agent/skills"));
        endpoints.put("tools", Map.of("method", "GET", "path", "/api/v1/agent/tools"));
        endpoints.put("memory", Map.of("method", "GET", "path", "/api/v1/agent/memory"));
        endpoints.put("checkpoints", Map.of("method", "POST", "path", "/api/v1/agent/checkpoint"));
        endpoints.put("mcp_servers", Map.of("method", "GET", "path", "/api/v1/mcp/servers"));

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("type", "bearer");
        auth.put("required", properties.getSecurity() != null
            && properties.getSecurity().getApiKey() != null
            && !properties.getSecurity().getApiKey().isBlank());

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("mode", "server_agent");
        runtime.put("tool_execution", "server");
        runtime.put("split_runtime", false);
        runtime.put("description", "The Java agent runs the agent runtime and tools on the server.");

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

    private String resolveModel() {
        String override = runtimeConfigService.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return properties.getModel().getModelName();
    }
}