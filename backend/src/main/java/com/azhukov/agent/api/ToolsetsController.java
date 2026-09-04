package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GET /v1/toolsets — list toolsets and their resolved tools.
 *
 * Mirrors Hermes' GET /v1/toolsets endpoint: returns each toolset's
 * enabled/configured state plus the concrete tool names it expands to.
 */
@RestController
@Slf4j
@Tag(name = "OpenAI-compatible", description = "Toolset listing and management")
public class ToolsetsController {

    private static final List<ToolsetMeta> HERMES_CONFIGURABLE_TOOLSETS = List.of(
        toolset("web", "🔍 Web Search & Scraping", "web_search, web_extract",
            "web_extract", "web_search"),
        toolset("browser", "🌐 Browser Automation", "navigate, click, type, scroll",
            "browser_back", "browser_cdp", "browser_click", "browser_console",
            "browser_dialog", "browser_exec", "browser_get_images", "browser_navigate",
            "browser_press", "browser_scroll", "browser_snapshot", "browser_type",
            "browser_vision", "web_search"),
        toolset("terminal", "💻 Terminal & Processes", "terminal, process",
            "process", "terminal"),
        toolset("file", "📁 File Operations", "read, write, patch, search",
            "patch", "read_file", "search_files", "write_file"),
        toolset("code_execution", "⚡ Code Execution", "execute_code",
            "execute_code"),
        toolset("vision", "👁️  Vision / Image Analysis", "vision_analyze",
            "vision_analyze"),
        toolset("video", "🎬 Video Analysis", "video_analyze (requires video-capable model)",
            "video_analyze"),
        toolset("image_gen", "🎨 Image Generation", "image_generate",
            "image_generate"),
        toolset("video_gen", "🎬 Video Generation", "video_generate (text/image/reference)",
            "video_generate", "xai_video_edit", "xai_video_extend"),
        toolset("x_search", "🐦 X (Twitter) Search", "x_search (requires xAI OAuth or XAI_API_KEY)",
            "x_search"),
        toolset("tts", "🔊 Text-to-Speech", "text_to_speech",
            "text_to_speech"),
        toolset("stt", "🎙️ Speech-to-Text", "voice transcription (gateway voice messages + voice mode)"),
        toolset("skills", "📚 Skills", "list, view, manage",
            "skill_manage", "skill_view", "skills_list"),
        toolset("todo", "📋 Task Planning", "todo",
            "todo"),
        toolset("memory", "💾 Memory", "persistent memory across sessions",
            "memory"),
        toolset("context_engine", "🧩 Context Engine", "runtime tools from the active context engine"),
        toolset("session_search", "🔎 Session Search", "search past conversations",
            "session_search"),
        toolset("clarify", "❓ Clarifying Questions", "clarify",
            "clarify"),
        toolset("delegation", "👥 Task Delegation", "delegate_task",
            "delegate_task"),
        toolset("cronjob", "⏰ Cron Jobs", "create/list/update/pause/resume/run, with optional attached skills",
            "cronjob"),
        toolset("homeassistant", "🏠 Home Assistant", "smart home device control",
            "ha_call_service", "ha_get_state", "ha_list_entities", "ha_list_services"),
        toolset("spotify", "🎵 Spotify", "playback, search, playlists, library",
            "spotify_albums", "spotify_devices", "spotify_library", "spotify_playback",
            "spotify_playlists", "spotify_queue", "spotify_search"),
        toolset("discord", "💬 Discord (read/participate)", "fetch messages, search members, create thread",
            "discord"),
        toolset("discord_admin", "🛡️  Discord Server Admin", "list channels/roles, pin, assign roles",
            "discord_admin"),
        toolset("yuanbao", "🤖 Yuanbao", "group info, member queries, DM",
            "yb_query_group_info", "yb_query_group_members", "yb_search_sticker",
            "yb_send_dm", "yb_send_sticker"),
        toolset(
            "computer_use",
            "🖱️  Computer Use (macOS/Windows/Linux)",
            "background desktop control via cua-driver",
            "computer_use"
        )
    );
    private static final Set<String> HERMES_CONFIGURABLE_TOOLSET_NAMES = HERMES_CONFIGURABLE_TOOLSETS.stream()
        .map(ToolsetMeta::name)
        .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> HERMES_INTERNAL_TOOLSET_NAMES = Set.of(
        "search",
        "browser-cdp",
        "project",
        "desktop_ui",
        "feishu_doc",
        "feishu_drive",
        "debugging",
        "safe",
        "coding",
        "hermes-acp",
        "hermes-api-server",
        "hermes-cli",
        "hermes-cron",
        "hermes-telegram",
        "hermes-discord",
        "hermes-whatsapp",
        "hermes-slack",
        "hermes-signal",
        "hermes-bluebubbles",
        "hermes-homeassistant",
        "hermes-email",
        "hermes-sms",
        "hermes-mattermost",
        "hermes-matrix",
        "hermes-dingtalk",
        "hermes-feishu",
        "hermes-wecom",
        "hermes-wecom-callback",
        "hermes-weixin",
        "hermes-qqbot",
        "hermes-yuanbao",
        "hermes-webhook",
        "hermes-gateway"
    );
    private static final Set<String> HERMES_DEFAULT_OFF_TOOLSETS = Set.of(
        "homeassistant",
        "spotify",
        "discord",
        "discord_admin",
        "video",
        "video_gen",
        "x_search",
        "a2a"
    );
    private static final Set<String> HERMES_CONFIG_ONLY_TOOLSETS = Set.of("stt");
    private static final Set<String> HERMES_API_SERVER_DEFAULTS = Set.of(
        "web",
        "terminal",
        "file",
        "vision",
        "image_gen",
        "skills",
        "browser",
        "todo",
        "memory",
        "session_search",
        "code_execution",
        "delegation",
        "cronjob",
        "homeassistant"
    );
    private static final Set<String> HERMES_INTERACTIVE_DEFAULTS = Set.of(
        "web",
        "terminal",
        "file",
        "vision",
        "image_gen",
        "skills",
        "browser",
        "tts",
        "todo",
        "memory",
        "session_search",
        "clarify",
        "code_execution",
        "delegation",
        "cronjob",
        "homeassistant",
        "computer_use"
    );
    private static final Set<String> HERMES_INTERACTIVE_COMPOSITES = Set.of(
        "hermes-cli",
        "hermes-cron",
        "hermes-telegram",
        "hermes-whatsapp",
        "hermes-slack",
        "hermes-signal",
        "hermes-bluebubbles",
        "hermes-homeassistant",
        "hermes-email",
        "hermes-sms",
        "hermes-mattermost",
        "hermes-matrix",
        "hermes-dingtalk",
        "hermes-feishu",
        "hermes-wecom",
        "hermes-wecom-callback",
        "hermes-weixin",
        "hermes-qqbot",
        "hermes-yuanbao"
    );
    private static final Map<String, Set<String>> TOOLSET_PLATFORM_RESTRICTIONS = Map.of(
        "discord", Set.of("discord"),
        "discord_admin", Set.of("discord")
    );
    private static final Map<String, String> PLATFORM_LABELS = Map.of(
        "api_server", "API Server",
        "cli", "CLI",
        "discord", "Discord"
    );

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;
    private final ProfileService profileService;

    @Autowired
    public ToolsetsController(ToolRegistry toolRegistry, AgentProperties properties, ProfileService profileService) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.profileService = profileService;
    }

    ToolsetsController(ToolRegistry toolRegistry, AgentProperties properties) {
        this(toolRegistry, properties, null);
    }

    @GetMapping({"/v1/toolsets", "/p/{profile}/v1/toolsets"})
    @Operation(summary = "List all toolsets with their tools and enabled state")
    public ResponseEntity<Map<String, Object>> listToolsets(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            return ResponseEntity.ok(listToolsetsPayload(profile.profile()));
        } catch (IOException | RuntimeException e) {
            log.warn("GET /v1/toolsets failed", e);
            return openAiError(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to enumerate toolsets", "server_error");
        }
    }

    @GetMapping({"/api/tools/toolsets", "/p/{profile}/api/tools/toolsets"})
    @Operation(summary = "List dashboard toolsets")
    public ResponseEntity<Object> listDashboardToolsets(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return ResponseEntity.status(profile.error().getStatusCode()).body(profile.error().getBody());
        }
        try {
            return ResponseEntity.ok(listDashboardToolsetsPayload(profile.profile()));
        } catch (IOException | RuntimeException e) {
            log.warn("GET /api/tools/toolsets failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to enumerate toolsets"));
        }
    }

    @GetMapping({"/api/tools/toolsets/{toolset}/config", "/p/{profile}/api/tools/toolsets/{toolset}/config"})
    @Operation(summary = "Get dashboard toolset provider configuration")
    public ResponseEntity<Map<String, Object>> toolsetConfig(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isKnownConfigurableToolset(toolset)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Unknown toolset: " + toolset));
        }
        try {
            return ResponseEntity.ok(toolsetConfigPayload(toolset, profile.profile()));
        } catch (IOException | RuntimeException e) {
            log.warn("GET /api/tools/toolsets/{}/config failed for profile {}", toolset, profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to read toolset configuration"));
        }
    }

    @GetMapping({"/api/tools/toolsets/{toolset}/models", "/p/{profile}/api/tools/toolsets/{toolset}/models"})
    @Operation(summary = "Return dashboard model catalog for toolset backends")
    public ResponseEntity<Map<String, Object>> toolsetModels(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @org.springframework.web.bind.annotation.RequestParam(name = "provider", required = false) String provider
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", toolset);
        response.put("has_models", false);
        response.put("provider", clean(provider));
        response.put("plugin", null);
        response.put("models", List.of());
        response.put("current", null);
        response.put("default", null);
        return ResponseEntity.ok(response);
    }

    Map<String, Object> listToolsetsPayload() {
        try {
            return listToolsetsPayload("default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default toolset profile", e);
        }
    }

    Map<String, Object> listToolsetsPayload(String profile) throws IOException {
        Set<String> availableToolsets = toolRegistry.getToolsets();
        Set<String> registryToolsets = availableToolsets != null ? availableToolsets : Set.of();
        ToolsetConfig config = toolsetConfig(profile);
        Set<String> enabledToolsets = expandedEnabledToolsets(apiServerToolsets(config), "api_server");

        List<Map<String, Object>> data = new ArrayList<>();

        for (ToolsetMeta meta : HERMES_CONFIGURABLE_TOOLSETS) {
            data.add(toolsetEntry(meta, enabledToolsets, config));
        }

        for (String toolset : registryToolsets.stream().sorted().toList()) {
            if (HERMES_CONFIGURABLE_TOOLSET_NAMES.contains(toolset)
                || HERMES_INTERNAL_TOOLSET_NAMES.contains(toolset)) {
                continue;
            }
            data.add(toolsetEntry(new ToolsetMeta(toolset, toolset, "Toolset: " + toolset, registryToolNames(toolset)),
                enabledToolsets, config));
        }

        return Map.of(
            "object", "list",
            "platform", "api_server",
            "data", data
        );
    }

    List<Map<String, Object>> listDashboardToolsetsPayload() {
        try {
            return listDashboardToolsetsPayload("default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default dashboard toolset profile", e);
        }
    }

    List<Map<String, Object>> listDashboardToolsetsPayload(String profile) throws IOException {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) listToolsetsPayload(profile).get("data");
        ToolsetConfig config = toolsetConfig(profile);
        Map<String, Set<String>> enabledByPlatform = new LinkedHashMap<>();
        return data.stream()
            .map(entry -> {
                String name = String.valueOf(entry.get("name"));
                String platform = toolsetConfigurationPlatform(name);
                Set<String> enabledToolsets = enabledByPlatform.computeIfAbsent(platform,
                    key -> expandedEnabledToolsets(dashboardRawToolsets(config, key), key));
                boolean enabled = HERMES_CONFIG_ONLY_TOOLSETS.contains(name)
                    ? configOnlyToolsetEnabled(config, name)
                    : enabledToolsets.contains(name) || enabledToolsets.contains(toolsetAlias(name));
                Map<String, Object> dashboardEntry = new LinkedHashMap<>(entry);
                dashboardEntry.put("label", guiToolsetLabel(String.valueOf(entry.get("label"))));
                dashboardEntry.put("platform", platform);
                dashboardEntry.put("platform_label", platformLabel(platform));
                dashboardEntry.put("enabled", enabled);
                dashboardEntry.put("available", enabled);
                return dashboardEntry;
            })
            .toList();
    }

    Map<String, Object> toolsetConfigPayload(String toolset) {
        try {
            return toolsetConfigPayload(toolset, "default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default toolset configuration", e);
        }
    }

    Map<String, Object> toolsetConfigPayload(String toolset, String profile) throws IOException {
        ToolsetConfig config = toolsetConfig(profile);
        String activeProvider = activeProvider(config, toolset);
        List<Map<String, Object>> providers = providersFor(config, toolset, activeProvider);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", toolset);
        payload.put("has_category", !providers.isEmpty());
        payload.put("providers", providers);
        payload.put("active_provider", activeProvider);
        if ("web".equals(toolset)) {
            payload.put("active_search_backend", activeWebBackend(config, "search"));
            payload.put("active_extract_backend", activeWebBackend(config, "extract"));
        }
        return payload;
    }

    private ResponseEntity<Map<String, Object>> openAiError(HttpStatus status, String message, String type) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("param", null);
        error.put("code", null);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }

    /** Toggle a toolset for NEW sessions (default-toolsets override). */
    @PostMapping({"/v1/toolsets/{toolset}/enable", "/p/{profile}/v1/toolsets/{toolset}/enable"})
    public ResponseEntity<Map<String, Object>> enable(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return toggle(pathProfile, queryProfile, toolset, true);
    }

    @PostMapping({"/v1/toolsets/{toolset}/disable", "/p/{profile}/v1/toolsets/{toolset}/disable"})
    public ResponseEntity<Map<String, Object>> disable(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return toggle(pathProfile, queryProfile, toolset, false);
    }

    @PutMapping({"/api/tools/toolsets/{toolset}", "/p/{profile}/api/tools/toolsets/{toolset}"})
    public ResponseEntity<Map<String, Object>> toggleDashboard(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestBody(required = false) ToolsetToggleBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        if (body == null || body.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("detail", "enabled is required"));
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> result;
        try {
            result = toggleDashboardToolset(toolset, body.enabled(), profile.profile());
        } catch (IOException e) {
            log.warn("PUT /api/tools/toolsets/{} failed for profile {}", toolset, profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to update toolset configuration"));
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Unknown toolset: " + toolset));
        }
        String platform = String.valueOf(result.get("platform"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("name", toolset);
        response.put("platform", platform);
        response.put("enabled", body.enabled());
        response.put("post_setup_started", null);
        return ResponseEntity.ok(response);
    }

    @PutMapping({"/api/tools/toolsets/{toolset}/model", "/p/{profile}/api/tools/toolsets/{toolset}/model"})
    @Operation(summary = "Reject dashboard model catalog writes not supported by Java port")
    public ResponseEntity<Map<String, Object>> selectToolsetModel(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestBody(required = false) ToolsetModelBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        if (body == null || !hasText(body.model())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "model is required"));
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        return ResponseEntity.badRequest().body(Map.of("detail", "Toolset has no model catalog: " + toolset));
    }

    @PutMapping({"/api/tools/toolsets/{toolset}/provider", "/p/{profile}/api/tools/toolsets/{toolset}/provider"})
    @Operation(summary = "Select a dashboard toolset provider where the Java port has runtime config")
    public ResponseEntity<Map<String, Object>> selectToolsetProvider(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestBody(required = false) ToolsetProviderBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        if (!isKnownConfigurableToolset(toolset)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Unknown toolset: " + toolset));
        }
        if (body == null || !hasText(body.provider())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "provider is required"));
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        String capability = clean(body.capability());
        if (capability != null) {
            if (!"web".equals(toolset)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("detail", "capability selection is only supported for the web toolset"));
            }
            if (!"search".equals(capability) && !"extract".equals(capability)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("detail", "Unknown capability: " + capability + " (expected 'search' or 'extract')"));
            }
            if ("extract".equals(capability)) {
                return notImplemented("web extract backend selection is not implemented in the Java port");
            }
        }

        String provider = body.provider().trim();
        String normalized;
        try {
            normalized = providerKeyFor(toolset, provider, profile.profile());
        } catch (IOException e) {
            log.warn("PUT /api/tools/toolsets/{}/provider failed for profile {}", toolset, profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to read toolset provider configuration"));
        }
        if (normalized == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("detail", "Unknown provider '" + provider + "' for toolset '" + toolset + "'"));
        }
        try {
            applyProviderSelection(toolset, normalized, capability, profile.profile());
        } catch (IOException e) {
            log.warn("PUT /api/tools/toolsets/{}/provider failed for profile {}", toolset, profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to update toolset provider configuration"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("name", toolset);
        response.put("provider", provider);
        if (capability != null) {
            response.put("capability", capability);
        }
        return ResponseEntity.ok(response);
    }

    @PutMapping({"/api/tools/toolsets/{toolset}/env", "/p/{profile}/api/tools/toolsets/{toolset}/env"})
    @Operation(summary = "Reject dashboard env writes because Java has no Hermes profile env store")
    public ResponseEntity<Map<String, Object>> saveToolsetEnv(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestBody(required = false) Map<String, Object> body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, stringValue(body != null ? body.get("profile") : null));
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isKnownConfigurableToolset(toolset)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Unknown toolset: " + toolset));
        }
        return notImplemented("toolset environment writes are not implemented in the Java port");
    }

    @PostMapping({"/api/tools/toolsets/{toolset}/post-setup", "/p/{profile}/api/tools/toolsets/{toolset}/post-setup"})
    @Operation(summary = "Reject dashboard post-setup actions not supported by Java port")
    public ResponseEntity<Map<String, Object>> runToolsetPostSetup(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String toolset,
        @RequestBody(required = false) ToolsetPostSetupBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body != null ? body.profile() : null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isKnownConfigurableToolset(toolset)) {
            return ResponseEntity.badRequest().body(Map.of("detail", "Unknown toolset: " + toolset));
        }
        if (body == null || !hasText(body.key())) {
            return ResponseEntity.badRequest().body(Map.of("detail", "key is required"));
        }
        return notImplemented("toolset post-setup actions are not implemented in the Java port");
    }

    @GetMapping({"/api/tools/terminal/backends", "/p/{profile}/api/tools/terminal/backends"})
    @Operation(summary = "Return Java port terminal backend status")
    public ResponseEntity<Map<String, Object>> terminalBackends(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("name", "local");
        local.put("label", "Local shell");
        local.put("description", "Run commands on this host");
        local.put("active", true);
        local.put("status", "ready");
        local.put("detail", "");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active", "local");
        response.put("backends", List.of(local));
        return ResponseEntity.ok(response);
    }

    @PutMapping({"/api/tools/terminal/backend", "/p/{profile}/api/tools/terminal/backend"})
    @Operation(summary = "Select Java port terminal backend")
    public ResponseEntity<Map<String, Object>> selectTerminalBackend(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestBody(required = false) TerminalBackendBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body != null ? body.profile() : null);
        if (profile.error() != null) {
            return profile.error();
        }
        String backend = body != null ? clean(body.backend()) : null;
        if (!"local".equals(backend)) {
            return ResponseEntity.badRequest()
                .body(Map.of("detail", "Unknown terminal backend: " + (backend != null ? backend : "")));
        }
        return ResponseEntity.ok(Map.of("ok", true, "backend", "local"));
    }

    @GetMapping({"/api/tools/computer-use/status", "/p/{profile}/api/tools/computer-use/status"})
    @Operation(summary = "Return Java port Computer Use readiness status")
    public ResponseEntity<Map<String, Object>> computerUseStatus(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("label", "cua-driver");
        check.put("status", "unavailable");
        check.put("message", "Computer Use is not implemented in the Java port");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("platform", platformSlug());
        response.put("platform_supported", false);
        response.put("installed", false);
        response.put("version", null);
        response.put("ready", false);
        response.put("can_grant", false);
        response.put("checks", List.of(check));
        response.put("accessibility", null);
        response.put("screen_recording", null);
        response.put("screen_recording_capturable", null);
        response.put("source", null);
        response.put("error", "Computer Use is not implemented in the Java port");
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/api/tools/computer-use/permissions/grant", "/p/{profile}/api/tools/computer-use/permissions/grant"})
    @Operation(summary = "Reject Computer Use permission grants outside Hermes cua-driver")
    public ResponseEntity<Map<String, Object>> grantComputerUsePermissions(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        return ResponseEntity.badRequest()
            .body(Map.of("detail", "Computer Use permission grants are not implemented in the Java port"));
    }

    private ResponseEntity<Map<String, Object>> toggle(
        String pathProfile,
        String queryProfile,
        String toolset,
        boolean enabled
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> result;
        try {
            result = toggleApiServerToolset(toolset, enabled, profile.profile());
        } catch (IOException e) {
            log.warn("POST /v1/toolsets/{}/{} failed for profile {}", toolset, enabled ? "enable" : "disable",
                profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to update toolset configuration"));
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toggleApiServerToolset(String toolset, boolean enabled, String profile)
        throws IOException {
        if (!isKnownConfigurableToolset(toolset)) {
            return Map.of("ok", false, "reason", "unknown toolset: " + toolset);
        }
        if (isDefaultProfile(profile)) {
            List<String> current = toggledToolsets(apiServerToolsets(), toolset, enabled, "api_server");
            AgentProperties.ApiProperties api = properties.getApi();
            if (api != null) {
                api.setChatCompletionToolsets(current);
            } else if (properties.getSkills() != null) {
                properties.getSkills().setDefaultToolsets(current);
            }
        } else {
            Map<String, Object> config = profileService.readConfig(profile);
            List<String> current = toggledToolsets(configPlatformToolsets(config, "api_server"),
                toolset, enabled, "api_server");
            writePlatformToolsets(config, "api_server", current);
            profileService.writeConfig(profile, config);
        }
        return Map.of("ok", true, "toolset", toolset, "enabled", enabled);
    }

    private Map<String, Object> toggleDashboardToolset(String toolset, boolean enabled) {
        try {
            return toggleDashboardToolset(toolset, enabled, "default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update default dashboard toolset", e);
        }
    }

    private Map<String, Object> toggleDashboardToolset(String toolset, boolean enabled, String profile)
        throws IOException {
        if (!isKnownConfigurableToolset(toolset)) {
            return Map.of("ok", false, "reason", "unknown toolset: " + toolset);
        }

        String platform = toolsetConfigurationPlatform(toolset);
        if (isDefaultProfile(profile)) {
            if (HERMES_CONFIG_ONLY_TOOLSETS.contains(toolset)) {
                AgentProperties.TranscriptionProperties transcription = properties.getTranscription();
                if (transcription != null) {
                    transcription.setEnabled(enabled);
                }
            } else if ("cli".equals(platform) && properties.getSkills() != null) {
                properties.getSkills().setDefaultToolsets(
                    toggledToolsets(dashboardRawToolsets(platform), toolset, enabled, platform)
                );
            }
        } else {
            Map<String, Object> config = profileService.readConfig(profile);
            if (HERMES_CONFIG_ONLY_TOOLSETS.contains(toolset)) {
                Map<String, Object> section = mutableSection(config, toolset);
                section.put("enabled", enabled);
                config.put(toolset, section);
            } else {
                List<String> current = toggledToolsets(configPlatformToolsets(config, platform),
                    toolset, enabled, platform);
                writePlatformToolsets(config, platform, current);
            }
            profileService.writeConfig(profile, config);
        }
        return Map.of("ok", true, "toolset", toolset, "enabled", enabled, "platform", platform);
    }

    private boolean isKnownConfigurableToolset(String toolset) {
        if (toolset == null || toolset.isBlank()) {
            return false;
        }
        if (HERMES_CONFIGURABLE_TOOLSET_NAMES.contains(toolset)) {
            return true;
        }
        Set<String> registryToolsets = toolRegistry.getToolsets();
        return registryToolsets != null && registryToolsets.contains(toolset);
    }

    private List<String> apiServerToolsets() {
        AgentProperties.ApiProperties api = properties.getApi();
        if (api != null) {
            return api.getChatCompletionToolsets();
        }
        return properties.getSkills() != null
            ? properties.getSkills().getDefaultToolsets()
            : List.of();
    }

    private List<String> apiServerToolsets(ToolsetConfig config) {
        if (config.defaultProfile()) {
            return apiServerToolsets();
        }
        return configPlatformToolsets(config.values(), "api_server");
    }

    private List<String> dashboardRawToolsets(String platform) {
        if ("cli".equals(platform) && properties.getSkills() != null) {
            return properties.getSkills().getDefaultToolsets();
        }
        return List.of(defaultToolsetForPlatform(platform));
    }

    private List<String> dashboardRawToolsets(ToolsetConfig config, String platform) {
        if (config.defaultProfile()) {
            return dashboardRawToolsets(platform);
        }
        return configPlatformToolsets(config.values(), platform);
    }

    private List<String> configPlatformToolsets(Map<String, Object> config, String platform) {
        Map<String, Object> platformToolsets = mapSection(config, "platform_toolsets");
        return listValue(platformToolsets.get(platform));
    }

    private void writePlatformToolsets(Map<String, Object> config, String platform, List<String> toolsets) {
        Map<String, Object> platformToolsets = mutableSection(config, "platform_toolsets");
        platformToolsets.put(platform, normalizeToolsetNames(toolsets));
        config.put("platform_toolsets", platformToolsets);
    }

    private List<String> toggledToolsets(List<String> rawToolsets, String toolset, boolean enabled, String platform) {
        Set<String> resolved = expandedEnabledToolsets(rawToolsets, platform);
        if (enabled) {
            resolved.add(toolset);
        } else {
            resolved.remove(toolset);
            resolved.remove(toolsetAlias(toolset));
        }
        return resolved.stream().sorted().toList();
    }

    private Set<String> expandedEnabledToolsets(List<String> rawToolsets, String platform) {
        Set<String> direct = new HashSet<>();
        Set<String> expanded = new HashSet<>();
        List<String> names = normalizeToolsetNames(rawToolsets);
        if (names.isEmpty()) {
            names = List.of(defaultToolsetForPlatform(platform));
        }

        for (String name : names) {
            if (HERMES_CONFIGURABLE_TOOLSET_NAMES.contains(name)) {
                if (toolsetAllowedForPlatform(name, platform)) {
                    direct.add(name);
                }
                continue;
            }

            Set<String> composite = compositeExpansion(name);
            if (!composite.isEmpty()) {
                composite.stream()
                    .filter(toolset -> toolsetAllowedForPlatform(toolset, platform))
                    .forEach(expanded::add);
                continue;
            }

            direct.add(name);
        }

        Set<String> defaultOff = new HashSet<>(HERMES_DEFAULT_OFF_TOOLSETS);
        if (defaultOff.contains(platform) && !TOOLSET_PLATFORM_RESTRICTIONS.containsKey(platform)) {
            defaultOff.remove(platform);
        }
        if (homeAssistantCredentialsPresent()) {
            defaultOff.remove("homeassistant");
        }
        if (xaiCredentialsPresent()) {
            defaultOff.remove("x_search");
            if (toolsetAllowedForPlatform("x_search", platform)) {
                expanded.add("x_search");
            }
        }
        expanded.removeAll(defaultOff);
        expanded.addAll(direct);
        return expanded;
    }

    private List<String> normalizeToolsetNames(List<String> rawToolsets) {
        if (rawToolsets == null) {
            return List.of();
        }
        return rawToolsets.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .toList();
    }

    private Set<String> compositeExpansion(String toolset) {
        if ("hermes-api-server".equals(toolset)) {
            return HERMES_API_SERVER_DEFAULTS;
        }
        if ("hermes-discord".equals(toolset)) {
            Set<String> defaults = new HashSet<>(HERMES_INTERACTIVE_DEFAULTS);
            defaults.add("discord");
            defaults.add("discord_admin");
            return defaults;
        }
        if (HERMES_INTERACTIVE_COMPOSITES.contains(toolset)) {
            return HERMES_INTERACTIVE_DEFAULTS;
        }
        return Set.of();
    }

    private String defaultToolsetForPlatform(String platform) {
        return switch (platform) {
            case "api_server" -> "hermes-api-server";
            case "discord" -> "hermes-discord";
            default -> "hermes-" + platform;
        };
    }

    private String toolsetConfigurationPlatform(String toolset) {
        Set<String> allowed = TOOLSET_PLATFORM_RESTRICTIONS.get(toolset);
        if (allowed == null || allowed.contains("cli")) {
            return "cli";
        }
        return allowed.stream().sorted().findFirst().orElse("cli");
    }

    private boolean toolsetAllowedForPlatform(String toolset, String platform) {
        Set<String> allowed = TOOLSET_PLATFORM_RESTRICTIONS.get(toolset);
        return allowed == null || allowed.contains(platform);
    }

    private String platformLabel(String platform) {
        return PLATFORM_LABELS.getOrDefault(platform, platform);
    }

    private boolean configOnlyToolsetEnabled(String toolset) {
        if (!"stt".equals(toolset)) {
            return false;
        }
        AgentProperties.TranscriptionProperties transcription = properties.getTranscription();
        return transcription != null && transcription.isEnabled();
    }

    private boolean configOnlyToolsetEnabled(ToolsetConfig config, String toolset) {
        if (config.defaultProfile()) {
            return configOnlyToolsetEnabled(toolset);
        }
        if (!"stt".equals(toolset)) {
            return false;
        }
        Map<String, Object> section = mapSection(config.values(), toolset);
        return booleanValue(section.get("enabled"), true);
    }

    private String providerKeyFor(String toolset, String provider) {
        try {
            return providerKeyFor(toolset, provider, "default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default provider selection", e);
        }
    }

    private String providerKeyFor(String toolset, String provider, String profile) throws IOException {
        String requested = clean(provider);
        if (requested == null) {
            return null;
        }
        ToolsetConfig config = toolsetConfig(profile);
        String requestedLower = requested.toLowerCase(Locale.ROOT);
        return providersFor(config, toolset, activeProvider(config, toolset)).stream()
            .map(row -> String.valueOf(row.get("name")))
            .filter(name -> requested.equalsIgnoreCase(name))
            .findFirst()
            .orElseGet(() -> switch (toolset) {
                case "web" -> switch (requestedLower) {
                    case "duckduckgo", "ddg" -> "ddg";
                    case "searxng", "searx" -> "searxng";
                    default -> null;
                };
                case "tts" -> switch (requestedLower) {
                    case "edge", "microsoft edge tts", "microsoft edge" -> "edge";
                    case "openai", "openai tts" -> "openai";
                    default -> null;
                };
                case "image_gen", "vision" -> switch (requestedLower) {
                    case "openai", "openai-compatible", "openai compatible" -> requestedLower;
                    default -> null;
                };
                case "stt" -> "openai".equals(requestedLower) ? "openai" : null;
                default -> null;
            });
    }

    private void applyProviderSelection(String toolset, String provider) {
        try {
            applyProviderSelection(toolset, provider, null, "default");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update default provider selection", e);
        }
    }

    private void applyProviderSelection(String toolset, String provider, String capability, String profile)
        throws IOException {
        if (!isDefaultProfile(profile)) {
            Map<String, Object> config = profileService.readConfig(profile);
            Map<String, Object> section = mutableSection(config, toolset);
            switch (toolset) {
                case "web" -> {
                    String key = capability == null ? "backend" : capability + "_backend";
                    section.put(key, provider);
                    config.put("web", section);
                }
                case "tts", "image_gen", "vision", "stt" -> {
                    section.put("provider", provider);
                    config.put(toolset, section);
                }
                default -> {
                    return;
                }
            }
            profileService.writeConfig(profile, config);
            return;
        }
        switch (toolset) {
            case "web" -> properties.getWeb().setSearchProvider(provider);
            case "tts" -> properties.getTts().setProvider(provider);
            case "image_gen" -> properties.getImageGen().setProvider(provider);
            case "vision" -> properties.getVision().setProvider(provider);
            case "stt" -> properties.getTranscription().setProvider(provider);
            default -> {
                // Known toolsets without provider categories have nothing to mutate.
            }
        }
    }

    private Map<String, Object> toolsetEntry(ToolsetMeta meta, Set<String> enabledToolsets) {
        try {
            return toolsetEntry(meta, enabledToolsets, toolsetConfig("default"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read default toolset configuration", e);
        }
    }

    private Map<String, Object> toolsetEntry(ToolsetMeta meta, Set<String> enabledToolsets, ToolsetConfig config) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", meta.name());
        entry.put("label", meta.label());
        entry.put("description", meta.description());
        entry.put("enabled", enabledToolsets.contains(meta.name()) || enabledToolsets.contains(toolsetAlias(meta.name())));
        entry.put("configured", configured(config, meta.name()));
        entry.put("tools", meta.tools());
        return entry;
    }

    private String toolsetAlias(String toolset) {
        return toolset != null && toolset.startsWith("mcp-") ? toolset.substring("mcp-".length()) : toolset;
    }

    private String guiToolsetLabel(String label) {
        String text = label != null ? label.trim() : "";
        if (text.isEmpty()) {
            return text;
        }
        int space = text.indexOf(' ');
        if (space <= 0) {
            return text;
        }
        String prefix = text.substring(0, space);
        boolean asciiAlnum = prefix.chars()
            .anyMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
        return asciiAlnum ? text : text.substring(space + 1).trim();
    }

    private static ToolsetMeta toolset(String name, String label, String description, String... tools) {
        return new ToolsetMeta(name, label, description, List.of(tools));
    }

    private List<String> registryToolNames(String toolset) {
        List<ToolDefinition> tools = toolRegistry.getDefinitions(Set.of(toolset));
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
            .map(ToolDefinition::name)
            .collect(Collectors.toCollection(HashSet::new))
            .stream()
            .sorted()
            .toList();
    }

    private boolean configured(String toolset) {
        return switch (toolset) {
            case "homeassistant" -> hasEnv("HASS_TOKEN") && hasEnv("HASS_URL");
            case "vision" -> visionConfigured();
            default -> true;
        };
    }

    private boolean configured(ToolsetConfig config, String toolset) {
        if (config.defaultProfile()) {
            return configured(toolset);
        }
        return switch (toolset) {
            case "homeassistant" -> {
                Map<String, Object> homeassistant = mapSection(config.values(), "homeassistant");
                yield hasText(stringValue(homeassistant.get("url")))
                    && hasText(stringValue(homeassistant.get("token")));
            }
            case "vision" -> profileVisionConfigured(config.values());
            default -> true;
        };
    }

    private boolean profileVisionConfigured(Map<String, Object> config) {
        Map<String, Object> vision = mapSection(config, "vision");
        if (hasText(stringValue(vision.get("api_key"))) || hasText(stringValue(vision.get("base_url")))) {
            return true;
        }
        Map<String, Object> auxiliary = mapSection(config, "auxiliary");
        return booleanValue(auxiliary.get("enabled"), false);
    }

    private boolean visionConfigured() {
        AgentProperties.VisionProperties vision = properties.getVision();
        if (vision != null && (hasText(vision.getApiKey()) || hasText(vision.getBaseUrl()))) {
            return true;
        }
        AgentProperties.AuxiliaryProperties auxiliary = properties.getAuxiliary();
        return auxiliary != null && auxiliary.isEnabled();
    }

    private boolean homeAssistantCredentialsPresent() {
        return hasEnv("HASS_TOKEN") && hasEnv("HASS_URL");
    }

    private boolean xaiCredentialsPresent() {
        return hasEnv("XAI_API_KEY");
    }

    private boolean hasEnv(String name) {
        return hasText(System.getenv(name));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String activeProvider(String toolset) {
        return switch (toolset) {
            case "web" -> hasText(properties.getWeb().getSearxngUrl())
                || "searxng".equalsIgnoreCase(properties.getWeb().getSearchProvider())
                    ? "searxng"
                    : "ddg";
            case "tts" -> properties.getTts().getProvider();
            case "image_gen" -> properties.getImageGen().getProvider();
            case "vision" -> properties.getVision().getProvider();
            case "stt" -> properties.getTranscription().getProvider();
            default -> null;
        };
    }

    private String activeProvider(ToolsetConfig config, String toolset) {
        if (config.defaultProfile()) {
            return activeProvider(toolset);
        }
        Map<String, Object> values = config.values();
        return switch (toolset) {
            case "web" -> {
                Map<String, Object> web = mapSection(values, "web");
                yield firstNonBlank(
                    stringValue(web.get("search_backend")),
                    stringValue(web.get("backend")),
                    stringValue(web.get("search_provider")),
                    hasText(stringValue(web.get("searxng_url"))) ? "searxng" : null,
                    "ddg");
            }
            case "tts" -> firstNonBlank(stringValue(mapSection(values, "tts").get("provider")), "edge");
            case "image_gen" -> firstNonBlank(stringValue(mapSection(values, "image_gen").get("provider")), "fal");
            case "vision" -> firstNonBlank(stringValue(mapSection(values, "vision").get("provider")), "openai-compatible");
            case "stt" -> firstNonBlank(stringValue(mapSection(values, "stt").get("provider")), "openai");
            default -> null;
        };
    }

    private String activeWebBackend(ToolsetConfig config, String capability) {
        if (config.defaultProfile()) {
            return "extract".equals(capability) ? "jsoup" : activeProvider("web");
        }
        Map<String, Object> web = mapSection(config.values(), "web");
        String capabilityKey = capability + "_backend";
        return firstNonBlank(
            stringValue(web.get(capabilityKey)),
            stringValue(web.get("backend")),
            "extract".equals(capability) ? "jsoup" : "ddg");
    }

    private List<Map<String, Object>> providersFor(String toolset, String activeProvider) {
        return switch (toolset) {
            case "web" -> List.of(
                providerRow("ddg", activeProvider, List.of(), true),
                providerRow("searxng", activeProvider, List.of(
                    envVar("AGENT_WEB_SEARXNG_URL", "SearXNG URL", hasText(properties.getWeb().getSearxngUrl())
                        || hasEnv("AGENT_WEB_SEARXNG_URL"))
                ), hasText(properties.getWeb().getSearxngUrl()) || hasEnv("AGENT_WEB_SEARXNG_URL"))
            );
            case "tts" -> List.of(
                providerRow("edge", activeProvider, List.of(), true),
                providerRow("openai", activeProvider, List.of(
                    envVar("AGENT_TTS_API_KEY", "OpenAI TTS API key", hasText(properties.getTts().getApiKey())
                        || hasEnv("AGENT_TTS_API_KEY"))
                ), hasText(properties.getTts().getApiKey()) || hasEnv("AGENT_TTS_API_KEY"))
            );
            case "image_gen" -> List.of(
                providerRow("openai", activeProvider, List.of(
                    envVar("AGENT_IMAGE_GEN_API_KEY", "Image generation API key", hasText(properties.getImageGen().getApiKey())
                        || hasEnv("AGENT_IMAGE_GEN_API_KEY"))
                ), hasText(properties.getImageGen().getApiKey()) || hasEnv("AGENT_IMAGE_GEN_API_KEY"))
            );
            case "vision" -> List.of(
                providerRow("openai-compatible", activeProvider, List.of(
                    envVar("AGENT_VISION_API_KEY", "Vision API key", hasText(properties.getVision().getApiKey())
                        || hasEnv("AGENT_VISION_API_KEY"))
                ), visionConfigured())
            );
            case "stt" -> List.of(
                providerRow("openai", activeProvider, List.of(
                    envVar("AGENT_TRANSCRIPTION_API_KEY", "Transcription API key", hasText(properties.getTranscription().getApiKey())
                        || hasEnv("AGENT_TRANSCRIPTION_API_KEY"))
                ), properties.getTranscription().isEnabled()
                    && (hasText(properties.getTranscription().getApiKey()) || hasEnv("AGENT_TRANSCRIPTION_API_KEY")))
            );
            default -> List.of();
        };
    }

    private List<Map<String, Object>> providersFor(ToolsetConfig config, String toolset, String activeProvider) {
        if (config.defaultProfile()) {
            return providersFor(toolset, activeProvider);
        }
        Map<String, Object> values = config.values();
        return switch (toolset) {
            case "web" -> {
                Map<String, Object> web = mapSection(values, "web");
                boolean searxngConfigured = hasText(stringValue(web.get("searxng_url")))
                    || hasEnv("AGENT_WEB_SEARXNG_URL");
                yield List.of(
                    providerRow("ddg", activeProvider, List.of(), true),
                    providerRow("searxng", activeProvider, List.of(
                        envVar("AGENT_WEB_SEARXNG_URL", "SearXNG URL", searxngConfigured)
                    ), searxngConfigured)
                );
            }
            case "tts" -> {
                Map<String, Object> tts = mapSection(values, "tts");
                boolean openAiConfigured = hasText(stringValue(tts.get("api_key"))) || hasEnv("AGENT_TTS_API_KEY");
                yield List.of(
                    providerRow("edge", activeProvider, List.of(), true),
                    providerRow("openai", activeProvider, List.of(
                        envVar("AGENT_TTS_API_KEY", "OpenAI TTS API key", openAiConfigured)
                    ), openAiConfigured)
                );
            }
            case "image_gen" -> {
                Map<String, Object> imageGen = mapSection(values, "image_gen");
                boolean openAiConfigured = hasText(stringValue(imageGen.get("api_key"))) || hasEnv("AGENT_IMAGE_GEN_API_KEY");
                yield List.of(
                    providerRow("openai", activeProvider, List.of(
                        envVar("AGENT_IMAGE_GEN_API_KEY", "Image generation API key", openAiConfigured)
                    ), openAiConfigured)
                );
            }
            case "vision" -> {
                Map<String, Object> vision = mapSection(values, "vision");
                boolean openAiConfigured = hasText(stringValue(vision.get("api_key")))
                    || hasText(stringValue(vision.get("base_url")))
                    || hasEnv("AGENT_VISION_API_KEY");
                yield List.of(
                    providerRow("openai-compatible", activeProvider, List.of(
                        envVar("AGENT_VISION_API_KEY", "Vision API key", openAiConfigured)
                    ), openAiConfigured)
                );
            }
            case "stt" -> {
                Map<String, Object> stt = mapSection(values, "stt");
                boolean openAiConfigured = hasText(stringValue(stt.get("api_key")))
                    || hasText(stringValue(mapSection(values, "transcription").get("api_key")))
                    || hasEnv("AGENT_TRANSCRIPTION_API_KEY");
                yield List.of(
                    providerRow("openai", activeProvider, List.of(
                        envVar("AGENT_TRANSCRIPTION_API_KEY", "Transcription API key", openAiConfigured)
                    ), configOnlyToolsetEnabled(config, "stt") && openAiConfigured)
                );
            }
            default -> List.of();
        };
    }

    private Map<String, Object> providerRow(String name, String activeProvider,
                                            List<Map<String, Object>> envVars,
                                            boolean ready) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("badge", "");
        row.put("tag", "");
        row.put("env_vars", envVars);
        row.put("post_setup", null);
        row.put("requires_nous_auth", false);
        row.put("is_active", name.equalsIgnoreCase(activeProvider == null ? "" : activeProvider));
        row.put("status", ready ? "ready" : "missing_credentials");
        return row;
    }

    private Map<String, Object> envVar(String key, String prompt, boolean isSet) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("prompt", prompt);
        row.put("url", null);
        row.put("default", null);
        row.put("is_set", isSet);
        return row;
    }

    private ToolsetConfig toolsetConfig(String profile) throws IOException {
        String resolved = profile != null ? profile : "default";
        if (isDefaultProfile(resolved)) {
            return new ToolsetConfig("default", true, Map.of());
        }
        return new ToolsetConfig(resolved, false, profileService.readConfig(resolved));
    }

    private boolean isDefaultProfile(String profile) {
        return profile == null || "default".equals(profile);
    }

    private ProfileResolution resolveProfileScope(String pathProfile, String queryProfile, String bodyProfile) {
        List<ResolvedProfile> resolved = new ArrayList<>();
        for (String raw : new String[] {pathProfile, queryProfile, bodyProfile}) {
            if (hasText(raw)) {
                ProfileResolution profile = normalizeProfile(raw);
                if (profile.error() != null) {
                    return profile;
                }
                resolved.add(new ResolvedProfile(raw, profile.profile()));
            }
        }
        String profile = resolved.isEmpty() ? "default" : resolved.get(0).profile();
        for (ResolvedProfile item : resolved) {
            if (!profile.equals(item.profile())) {
                return ProfileResolution.error(badRequest("profile values do not match"));
            }
        }
        if (!isDefaultProfile(profile) && profileService == null) {
            return ProfileResolution.error(notImplemented(
                "profile-scoped toolsets are not available in this Java agent configuration"));
        }
        if (profileService != null && !profileService.knownProfile(profile)) {
            return ProfileResolution.error(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Unknown profile: " + profile)));
        }
        return ProfileResolution.ok(profile);
    }

    private ProfileResolution normalizeProfile(String rawProfile) {
        try {
            String profile = profileService != null
                ? profileService.normalizeProfileName(rawProfile)
                : rawProfile.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(profile)) {
                return ProfileResolution.error(badRequest("profile=all is not supported for toolsets"));
            }
            if (profileService != null) {
                profileService.validateProfileName(profile);
            } else if (!isDefaultProfile(profile)) {
                return ProfileResolution.error(notImplemented(
                    "profile-scoped toolsets are not available in this Java agent configuration"));
            }
            return ProfileResolution.ok(profile);
        } catch (IllegalArgumentException e) {
            return ProfileResolution.error(badRequest(e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private Map<String, Object> mapSection(Map<String, Object> parent, String key) {
        Object raw = parent != null ? parent.get(key) : null;
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((rawKey, value) -> {
                if (rawKey != null) {
                    result.put(String.valueOf(rawKey), value);
                }
            });
            return result;
        }
        return Map.of();
    }

    private Map<String, Object> mutableSection(Map<String, Object> parent, String key) {
        Object raw = parent.get(key);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((rawKey, value) -> {
                if (rawKey != null) {
                    result.put(String.valueOf(rawKey), value);
                }
            });
        }
        return result;
    }

    private List<String> listValue(Object raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = stringValue(item);
                if (hasText(value)) {
                    values.add(value.trim());
                }
            }
        } else {
            String value = stringValue(raw);
            if (hasText(value)) {
                for (String item : value.split(",")) {
                    if (hasText(item)) {
                        values.add(item.trim());
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private boolean booleanValue(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        String value = stringValue(raw);
        if (!hasText(value)) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            case "0", "false", "no", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail));
    }

    private static String platformSlug() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os.isBlank() ? "unknown" : os;
    }

    private record ToolsetMeta(String name, String label, String description, List<String> tools) {}
    private record ToolsetConfig(String profile, boolean defaultProfile, Map<String, Object> values) {}
    private record ResolvedProfile(String raw, String profile) {}
    private record ProfileResolution(String profile, ResponseEntity<Map<String, Object>> error) {
        private static ProfileResolution ok(String profile) {
            return new ProfileResolution(profile, null);
        }

        private static ProfileResolution error(ResponseEntity<Map<String, Object>> error) {
            return new ProfileResolution(null, error);
        }
    }
    private record ToolsetToggleBody(Boolean enabled, String profile) {}
    private record ToolsetModelBody(String model, String provider, String profile) {}
    private record ToolsetProviderBody(String provider, String capability, String profile) {}
    private record ToolsetPostSetupBody(String key, String profile) {}
    private record TerminalBackendBody(String backend, String profile) {}
}
