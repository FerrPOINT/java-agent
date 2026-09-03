package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Small, safe subset of Hermes dashboard system/config routes.
 *
 * <p>The Python Hermes dashboard mutates YAML, .env files, OAuth state, and
 * action logs. The Java port does not have that profile-aware config store, so
 * write endpoints fail explicitly instead of pretending to persist secrets.</p>
 */
@RestController
@Tag(name = "Hermes-compatible", description = "Dashboard status/config compatibility")
public class DashboardSystemController {

    private static final Object INSTALL_ID_LOCK = new Object();
    private static final SecureRandom INSTALL_ID_RANDOM = new SecureRandom();
    private static volatile String installIdCache;

    private static final Set<String> DASHBOARD_LOG_FILES = Set.of(
        "agent",
        "errors",
        "gateway",
        "gui",
        "desktop",
        "mcp");

    private static final Set<String> DASHBOARD_ACTION_NAMES = Set.of(
        "gateway-restart",
        "gateway-start",
        "gateway-stop",
        "hermes-update",
        "doctor",
        "security-audit",
        "backup",
        "import",
        "checkpoints-prune",
        "skills-install",
        "skills-uninstall",
        "skills-update",
        "curator-run",
        "prompt-size",
        "dump",
        "config-migrate",
        "tools-post-setup");

    private static final Set<String> FONT_CHOICES = Set.of(
        "theme",
        "system-sans",
        "system-serif",
        "system-mono",
        "inter",
        "ibm-plex-sans",
        "work-sans",
        "atkinson-hyperlegible",
        "dm-sans",
        "spectral",
        "fraunces",
        "source-serif",
        "jetbrains-mono",
        "ibm-plex-mono",
        "space-mono");

    private final AgentProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ProfileService profileService;
    private volatile String dashboardTheme = "default";
    private volatile String dashboardFont = "theme";

    @Autowired
    public DashboardSystemController(AgentProperties properties,
                                     RuntimeConfigService runtimeConfigService,
                                     ProfileService profileService) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.profileService = profileService;
    }

    DashboardSystemController(AgentProperties properties, RuntimeConfigService runtimeConfigService) {
        this(properties, runtimeConfigService, null);
    }

    @GetMapping({"/api/status", "/p/{profile}/api/status"})
    @Operation(summary = "Return Hermes desktop dashboard status shape")
    public ResponseEntity<Map<String, Object>> status(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "status");
        if (profile.error() != null) {
            return profile.error();
        }
        Path profileHome;
        String configPath;
        try {
            profileHome = profileHome(profile.profile());
            configPath = statusConfigPath(profile.profile());
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read profile status");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        int activeAgents = 0;
        response.put("active_sessions", 0);
        response.put("active_agents", activeAgents);
        response.put("auth_flows", List.of());
        response.put("auth_providers", List.of());
        response.put("auth_required", false);
        response.put("can_update_hermes", false);
        response.put("components", statusComponents());
        response.put("config_path", configPath);
        response.put("config_version", 0);
        response.put("disk", Map.of("pressure", "unknown"));
        response.put("env_path", profileHome.resolve(".env").toString());
        response.put("gateway_busy", false);
        response.put("gateway_drainable", true);
        response.put("gateway_exit_reason", null);
        response.put("gateway_health_url", null);
        response.put("gateway_mode", "single");
        response.put("gateway_pid", ProcessHandle.current().pid());
        response.put("gateway_platforms", Map.of());
        response.put("gateway_running", true);
        response.put("gateway_state", "running");
        response.put("gateway_updated_at", Instant.now().toString());
        String installId = installId();
        if (installId != null) {
            response.put("install_id", installId);
        }
        response.put("memory", Map.of("pressure", "unknown"));
        response.put("nous_session_valid", "unknown");
        response.put("overall", "ok");
        response.put("profiles", statusProfileNames());
        response.put("restart_drain_timeout", 0);
        response.put("hermes_home", profileHome.toString());
        response.put("latest_config_version", 0);
        response.put("release_date", "");
        response.put("version", implementationVersion());
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> statusComponents() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("gateway", Map.of("status", "ok", "state", "running"));
        components.put("dashboard", Map.of("status", "ok"));
        components.put("storage", Map.of("status", "ok"));
        components.put("platforms", Map.of("status", "ok", "configured", 0, "connected", 0));
        return components;
    }

    private Path profileHome(String profile) {
        if (!isDefaultProfile(profile) && profileService != null) {
            return profileService.profilePath(profile);
        }
        return hermesHome();
    }

    private String statusConfigPath(String profile) throws IOException {
        if (!isDefaultProfile(profile) && profileService != null) {
            return profileService.configPath(profile).toString();
        }
        return "classpath:application.yml";
    }

    private List<String> statusProfileNames() {
        if (profileService == null) {
            return List.of("default");
        }
        return profileService.listProfileRows().stream()
            .map(row -> String.valueOf(row.get("name")))
            .filter(DashboardSystemController::hasText)
            .toList();
    }

    @GetMapping("/api/auth/providers")
    @Operation(summary = "Return dashboard auth-provider discovery fallback")
    public ResponseEntity<Map<String, Object>> authProviders() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("detail", "no auth providers registered"));
    }

    @GetMapping("/api/auth/me")
    @Operation(summary = "Reject dashboard identity probe when the Java port is ungated")
    public ResponseEntity<Map<String, Object>> authMe() {
        return status(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @PostMapping("/api/auth/ws-ticket")
    @Operation(summary = "Reject dashboard WS ticket mint when gated auth is not enabled")
    public ResponseEntity<Map<String, Object>> authWsTicket() {
        return status(HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Render dashboard-auth unavailable login fallback")
    public ResponseEntity<String> loginPage() {
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
            .body("<!doctype html><html><body><h1>Dashboard authentication unavailable</h1>"
                + "<p>no auth providers registered</p></body></html>");
    }

    @GetMapping("/auth/login")
    @Operation(summary = "Reject dashboard OAuth login when no auth providers are registered")
    public ResponseEntity<Map<String, Object>> authLogin(
            @RequestParam(name = "provider", required = false, defaultValue = "") String provider) {
        return status(HttpStatus.NOT_FOUND, "Unknown provider: '" + defaultIfBlank(provider, "") + "'");
    }

    @GetMapping("/auth/native/authorize")
    @Operation(summary = "Reject native dashboard auth authorize flow when no providers are registered")
    public ResponseEntity<Map<String, Object>> authNativeAuthorize(
            @RequestParam(name = "provider", required = false, defaultValue = "") String provider,
            @RequestParam(name = "code_challenge", required = false, defaultValue = "") String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false, defaultValue = "") String codeChallengeMethod,
            @RequestParam(name = "redirect_uri", required = false, defaultValue = "") String redirectUri) {
        if (!"S256".equalsIgnoreCase(codeChallengeMethod)) {
            return status(HttpStatus.BAD_REQUEST, "code_challenge_method must be S256");
        }
        if (!hasText(codeChallenge)) {
            return status(HttpStatus.BAD_REQUEST, "code_challenge required");
        }
        ResponseEntity<Map<String, Object>> redirectError = validateLoopbackRedirectUri(redirectUri);
        if (redirectError != null) {
            return redirectError;
        }
        return status(HttpStatus.NOT_FOUND, "Unknown provider: '" + defaultIfBlank(provider, "") + "'");
    }

    @GetMapping("/auth/callback")
    @Operation(summary = "Reject dashboard OAuth callback without Java dashboard-auth PKCE state")
    public ResponseEntity<Map<String, Object>> authCallback() {
        return status(HttpStatus.BAD_REQUEST, "Missing PKCE state cookie");
    }

    @PostMapping("/auth/password-login")
    @Operation(summary = "Reject dashboard password login when no password providers are registered")
    public ResponseEntity<Map<String, Object>> authPasswordLogin(
            @RequestBody(required = false) PasswordLoginBody body) {
        return status(HttpStatus.NOT_FOUND, "Unknown provider");
    }

    @PostMapping("/auth/native/token")
    @Operation(summary = "Reject native dashboard auth token exchange without a Java auth broker")
    public ResponseEntity<Map<String, Object>> authNativeToken(
            @RequestBody(required = false) NativeTokenBody body) {
        return status(HttpStatus.BAD_REQUEST, "Invalid or expired authorization code.");
    }

    @PostMapping("/auth/native/refresh")
    @Operation(summary = "Reject native dashboard auth refresh when no providers are registered")
    public ResponseEntity<Map<String, Object>> authNativeRefresh(
            @RequestBody(required = false) NativeRefreshBody body) {
        if (body == null || !hasText(body.refreshToken())) {
            return status(HttpStatus.BAD_REQUEST, "refresh_token required");
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of(
                "error", "session_expired",
                "detail", "Refresh token expired or invalid; start a new sign-in."));
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "Clear dashboard auth session fallback and redirect to login")
    public ResponseEntity<Void> authLogout() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "/login")
            .build();
    }

    @GetMapping("/api/ssh/ownership")
    @Operation(summary = "Return inactive SSH ownership proof status")
    public ResponseEntity<Map<String, Object>> sshOwnership() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("detail", "SSH ownership is not active"));
    }

    @GetMapping("/api/portal")
    @Operation(summary = "Return read-only Nous Portal status fallback")
    public Map<String, Object> portal() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logged_in", false);
        response.put("portal_url", null);
        response.put("inference_url", null);
        response.put("provider", defaultIfBlank(properties.getModel().getProvider(), ""));
        response.put("subscription_url", "https://portal.nousresearch.com/manage-subscription");
        response.put("features", List.of());
        return response;
    }

    @GetMapping("/api/system/stats")
    @Operation(summary = "Return host and JVM stats in Hermes dashboard shape")
    public Map<String, Object> systemStats() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("os", System.getProperty("os.name", ""));
        response.put("os_release", System.getProperty("os.version", ""));
        response.put("os_version", System.getProperty("os.version", ""));
        response.put("platform", System.getProperty("os.name", "") + "-" + System.getProperty("os.version", ""));
        response.put("arch", System.getProperty("os.arch", ""));
        response.put("hostname", hostname());
        response.put("python_version", System.getProperty("java.version", ""));
        response.put("python_impl", "Java " + System.getProperty("java.vm.name", ""));
        response.put("hermes_version", implementationVersion());
        response.put("cpu_count", runtime.availableProcessors());
        response.put("memory", Map.of(
            "total", runtime.maxMemory(),
            "available", runtime.maxMemory() - usedMemory,
            "used", usedMemory,
            "percent", runtime.maxMemory() > 0 ? (usedMemory * 100.0d / runtime.maxMemory()) : 0.0d));
        diskStats().ifPresent(disk -> response.put("disk", disk));
        response.put("process", Map.of(
            "pid", ProcessHandle.current().pid(),
            "rss", usedMemory,
            "create_time", ProcessHandle.current().info().startInstant().map(Instant::getEpochSecond).orElse(0L),
            "num_threads", ManagementFactory.getThreadMXBean().getThreadCount()));
        response.put("psutil", false);
        return response;
    }

    @GetMapping({"/api/dashboard/themes", "/p/{profile}/api/dashboard/themes"})
    @Operation(summary = "Return built-in dashboard theme catalog")
    public ResponseEntity<Map<String, Object>> dashboardThemes(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "dashboard");
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            return ResponseEntity.ok(Map.of(
                "themes", builtInDashboardThemes(),
                "active", dashboardPreference(profile.profile(), "theme", dashboardTheme)));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read dashboard profile preference");
        }
    }

    @PutMapping({"/api/dashboard/theme", "/p/{profile}/api/dashboard/theme"})
    @Operation(summary = "Set in-process dashboard theme preference")
    public ResponseEntity<Map<String, Object>> setDashboardTheme(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) ThemeSetBody body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body != null ? body.profile() : null, "dashboard");
        if (profile.error() != null) {
            return profile.error();
        }
        String theme = body != null && clean(body.name()) != null ? body.name().trim() : "default";
        try {
            if (isDefaultProfile(profile.profile())) {
                dashboardTheme = theme;
            } else {
                writeDashboardPreference(profile.profile(), "theme", theme);
            }
            return ResponseEntity.ok(Map.of("ok", true, "theme", theme));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write dashboard profile preference");
        }
    }

    @GetMapping({"/api/dashboard/font", "/p/{profile}/api/dashboard/font"})
    @Operation(summary = "Return dashboard font preference")
    public ResponseEntity<Map<String, Object>> dashboardFont(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "dashboard");
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            return ResponseEntity.ok(Map.of("font", dashboardPreference(profile.profile(), "font", dashboardFont)));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read dashboard profile preference");
        }
    }

    @PutMapping({"/api/dashboard/font", "/p/{profile}/api/dashboard/font"})
    @Operation(summary = "Set in-process dashboard font preference")
    public ResponseEntity<Map<String, Object>> setDashboardFont(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) FontSetBody body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body != null ? body.profile() : null, "dashboard");
        if (profile.error() != null) {
            return profile.error();
        }
        String requested = body != null ? clean(body.font()) : null;
        String font = requested != null && FONT_CHOICES.contains(requested) ? requested : "theme";
        try {
            if (isDefaultProfile(profile.profile())) {
                dashboardFont = font;
            } else {
                writeDashboardPreference(profile.profile(), "font", font);
            }
            return ResponseEntity.ok(Map.of("ok", true, "font", font));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write dashboard profile preference");
        }
    }

    @GetMapping("/api/egress/status")
    @Operation(summary = "Return dashboard egress proxy status fallback")
    public Map<String, Object> egressStatus() {
        return Map.of("text", "Egress proxy is not configured in the Java port.");
    }

    @GetMapping("/api/logs")
    @Operation(summary = "Return an empty dashboard log tail for the Java port")
    public ResponseEntity<Map<String, Object>> logs(
        @RequestParam(name = "lines", required = false) Integer lines,
        @RequestParam(name = "file", required = false) String file
    ) {
        String logFile = clean(file) != null ? file.trim() : "agent";
        if (!DASHBOARD_LOG_FILES.contains(logFile)) {
            return status(HttpStatus.BAD_REQUEST, "Unknown log file: " + logFile);
        }
        return ResponseEntity.ok(Map.of(
            "file", logFile,
            "lines", List.of()));
    }

    @GetMapping("/api/actions/{name}/status")
    @Operation(summary = "Return inactive dashboard action status")
    public ResponseEntity<Map<String, Object>> actionStatus(
        @PathVariable String name,
        @RequestParam(name = "lines", required = false) Integer lines
    ) {
        if (!DASHBOARD_ACTION_NAMES.contains(name)) {
            return status(HttpStatus.NOT_FOUND, "Unknown action: " + name);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", name);
        response.put("pid", null);
        response.put("running", false);
        response.put("exit_code", null);
        response.put("lines", List.of());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/gateway/restart")
    @Operation(summary = "Reject dashboard gateway restart not supported by Java port")
    public ResponseEntity<Map<String, Object>> restartGateway() {
        return notImplemented("gateway restart is not implemented in the Java port");
    }

    @PostMapping("/api/gateway/start")
    @Operation(summary = "Reject dashboard gateway start not supported by Java port")
    public ResponseEntity<Map<String, Object>> startGateway() {
        return notImplemented("gateway start is not implemented in the Java port");
    }

    @PostMapping("/api/gateway/stop")
    @Operation(summary = "Reject dashboard gateway stop not supported by Java port")
    public ResponseEntity<Map<String, Object>> stopGateway() {
        return notImplemented("gateway stop is not implemented in the Java port");
    }

    @PostMapping("/api/gateway/drain")
    @Operation(summary = "Reject dashboard gateway drain not supported by Java port")
    public ResponseEntity<Map<String, Object>> drainGateway(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Object rawAction = body != null ? body.get("action") : null;
        String action = rawAction == null ? "drain" : String.valueOf(rawAction).trim().toLowerCase(Locale.ROOT);
        if (!"drain".equals(action) && !"cancel".equals(action)) {
            return status(
                HttpStatus.BAD_REQUEST,
                "Unknown drain action '" + action + "'; expected 'drain' or 'cancel'");
        }
        return notImplemented("gateway drain is not implemented in the Java port");
    }

    @PostMapping("/api/hermes/update")
    @Operation(summary = "Return managed-runtime update refusal for Java agent")
    public Map<String, Object> updateHermes() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("pid", null);
        response.put("name", "hermes-update");
        response.put("error", "dashboard_update_managed_externally");
        response.put("message", "Java agent updates are managed outside the Hermes dashboard.");
        response.put("update_command", "managed outside dashboard");
        return response;
    }

    @GetMapping("/api/hermes/update/check")
    @Operation(summary = "Return Java agent update status in Hermes dashboard shape")
    public Map<String, Object> checkHermesUpdate(
        @RequestParam(name = "force", required = false) Boolean force
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("install_method", "managed-runtime");
        response.put("current_version", implementationVersion());
        response.put("behind", null);
        response.put("update_available", false);
        response.put("can_apply", false);
        response.put("update_command", "managed outside dashboard");
        response.put("message", "Hermes updates are managed outside this dashboard in containerized environments.");
        response.put("commits", List.of());
        return response;
    }

    @GetMapping("/api/hermes/update/receipt")
    @Operation(summary = "Return absent update receipt status for Java-managed deployments")
    public ResponseEntity<Map<String, Object>> hermesUpdateReceipt() {
        return status(HttpStatus.NOT_FOUND, "No update receipt found (no `hermes update` run recorded).");
    }

    @PostMapping("/api/ops/doctor")
    @Operation(summary = "Reject dashboard doctor action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runDoctor() {
        return notImplemented("doctor action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/prompt-size")
    @Operation(summary = "Reject dashboard prompt-size action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runPromptSize() {
        return notImplemented("prompt-size action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/dump")
    @Operation(summary = "Reject dashboard dump action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runDump() {
        return notImplemented("dump action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/config-migrate")
    @Operation(summary = "Reject dashboard config migration action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runConfigMigrate() {
        return notImplemented("config migration action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/security-audit")
    @Operation(summary = "Reject dashboard security audit action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runSecurityAudit() {
        return notImplemented("security audit action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/backup")
    @Operation(summary = "Reject dashboard backup action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runBackup(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("backup action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/import")
    @Operation(summary = "Reject dashboard import action not supported by Java port")
    public ResponseEntity<Map<String, Object>> runImport(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String archive = body != null && body.get("archive") instanceof String rawArchive ? rawArchive.trim() : "";
        if (!hasText(archive)) {
            return status(HttpStatus.BAD_REQUEST, "archive path is required");
        }
        Path archivePath;
        try {
            archivePath = Path.of(archive);
        } catch (RuntimeException e) {
            return status(HttpStatus.BAD_REQUEST, "Invalid archive path");
        }
        if (!Files.isRegularFile(archivePath)) {
            return status(HttpStatus.NOT_FOUND, "Archive not found: " + archive);
        }
        return notImplemented("import action is not implemented in the Java port");
    }

    @PostMapping("/api/ops/import-upload")
    @Operation(summary = "Reject dashboard import upload not supported by Java port")
    public ResponseEntity<Map<String, Object>> runImportUpload() {
        return notImplemented("import upload is not implemented in the Java port");
    }

    @GetMapping("/api/ops/backup/download")
    @Operation(summary = "Reject dashboard backup download not supported by Java port")
    public ResponseEntity<Map<String, Object>> downloadBackup(
        @RequestParam(name = "archive", required = false) String archive
    ) {
        if (!hasText(archive)) {
            return status(HttpStatus.BAD_REQUEST, "archive is required");
        }
        Path archivePath;
        try {
            archivePath = Path.of(archive);
        } catch (RuntimeException e) {
            return status(HttpStatus.BAD_REQUEST, "Invalid backup path");
        }
        if (!Files.isRegularFile(archivePath)) {
            return status(HttpStatus.NOT_FOUND, "Backup not found");
        }
        return notImplemented("backup download is not implemented in the Java port");
    }

    @PostMapping("/api/ops/debug-share")
    @Operation(summary = "Reject dashboard debug-share upload not supported by Java port")
    public ResponseEntity<Map<String, Object>> runDebugShare(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("debug share is not implemented in the Java port");
    }

    @PutMapping("/api/dashboard/plugin-providers")
    @Operation(summary = "Reject dashboard plugin provider persistence not supported by Java port")
    public ResponseEntity<Map<String, Object>> savePluginProviders(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("plugin provider persistence is not implemented in the Java port");
    }

    @GetMapping("/api/ops/hooks")
    @Operation(summary = "Return empty shell hook catalog for the Java port")
    public Map<String, Object> hooks() {
        return Map.of(
            "hooks", List.of(),
            "valid_events", List.of());
    }

    @PostMapping("/api/ops/hooks")
    @Operation(summary = "Reject dashboard shell hook creation not supported by Java port")
    public ResponseEntity<Map<String, Object>> createHook(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        if (!hasText(bodyString(body, "event")) || !hasText(bodyString(body, "command"))) {
            return status(HttpStatus.BAD_REQUEST, "event and command are required");
        }
        return notImplemented("shell hook creation is not implemented in the Java port");
    }

    @DeleteMapping("/api/ops/hooks")
    @Operation(summary = "Reject dashboard shell hook deletion not supported by Java port")
    public ResponseEntity<Map<String, Object>> deleteHook(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        if (!hasText(bodyString(body, "event")) || !hasText(bodyString(body, "command"))) {
            return status(HttpStatus.BAD_REQUEST, "event and command are required");
        }
        return notImplemented("shell hook deletion is not implemented in the Java port");
    }

    @GetMapping("/api/ops/checkpoints")
    @Operation(summary = "List rollback checkpoint storage in Hermes dashboard shape")
    public Map<String, Object> checkpoints() {
        Path checkpointRoot = hermesHome().resolve("checkpoints");
        if (!Files.isDirectory(checkpointRoot)) {
            return Map.of("sessions", List.of(), "total_bytes", 0L);
        }

        List<Map<String, Object>> sessions;
        try (Stream<Path> stream = Files.list(checkpointRoot)) {
            sessions = stream
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(DashboardSystemController::checkpointSession)
                .toList();
        } catch (IOException | SecurityException e) {
            sessions = List.of();
        }
        long totalBytes = sessions.stream()
            .mapToLong(session -> ((Number) session.getOrDefault("bytes", 0L)).longValue())
            .sum();
        return Map.of(
            "sessions", sessions,
            "total_bytes", totalBytes);
    }

    @PostMapping("/api/ops/checkpoints/prune")
    @Operation(summary = "Reject dashboard checkpoint prune not supported by Java port")
    public ResponseEntity<Map<String, Object>> pruneCheckpoints() {
        return notImplemented("checkpoint pruning is not implemented in the Java port");
    }

    @GetMapping({"/api/config", "/p/{profile}/api/config"})
    @Operation(summary = "Return sanitized Java agent config in Hermes dashboard shape")
    public ResponseEntity<Map<String, Object>> config(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "config");
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            return ResponseEntity.ok(sanitizedConfig(profile.profile(), true));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read profile config");
        }
    }

    @GetMapping({"/api/config/defaults", "/p/{profile}/api/config/defaults"})
    @Operation(summary = "Return sanitized Java agent defaults")
    public ResponseEntity<Map<String, Object>> configDefaults(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "config");
        if (profile.error() != null) {
            return profile.error();
        }
        return ResponseEntity.ok(sanitizedGlobalConfig(false));
    }

    @GetMapping({"/api/config/raw", "/p/{profile}/api/config/raw"})
    @Operation(summary = "Return sanitized raw config text for dashboard editor compatibility")
    public ResponseEntity<Map<String, Object>> configRaw(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "config");
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("yaml", rawConfigYaml(profile.profile()));
            response.put("path", rawConfigPath(profile.profile()));
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read raw profile config");
        }
    }

    @PutMapping({"/api/config/raw", "/p/{profile}/api/config/raw"})
    @Operation(summary = "Reject raw config writes not supported by Java port")
    public ResponseEntity<Map<String, Object>> saveConfigRaw(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "config");
        if (profile.error() != null) {
            return profile.error();
        }
        if (isDefaultProfile(profile.profile())) {
            return notImplemented("raw dashboard config writes are not implemented in the Java port");
        }
        String yamlText = rawConfigBodyText(body);
        try {
            profileService.writeRawConfig(profile.profile(), yamlText);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return status(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write raw profile config");
        }
    }

    @GetMapping({"/api/config/schema", "/p/{profile}/api/config/schema"})
    @Operation(summary = "Return minimal dashboard config schema")
    public ResponseEntity<Map<String, Object>> configSchema(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "config");
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("model.provider", field("Model provider", "string", "model"));
        fields.put("model.default", field("Default model", "string", "model"));
        fields.put("model.base_url", field("OpenAI-compatible base URL", "string", "model"));
        fields.put("model.max_tokens", field("Maximum output tokens", "number", "model"));
        fields.put("web.search_provider", field("Web search provider", "select", "tools",
            List.of("ddg", "searxng")));
        fields.put("tts.provider", field("Text-to-speech provider", "select", "tools",
            List.of("edge", "openai")));
        fields.put("stt.enabled", field("Speech-to-text enabled", "boolean", "tools"));
        return ResponseEntity.ok(Map.of(
            "fields", fields,
            "category_order", List.of("model", "tools")));
    }

    @PutMapping({"/api/config", "/p/{profile}/api/config"})
    @Operation(summary = "Reject dashboard config writes not supported by Java port")
    public ResponseEntity<Map<String, Object>> saveConfig(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "config");
        if (profile.error() != null) {
            return profile.error();
        }
        if (isDefaultProfile(profile.profile())) {
            return notImplemented("dashboard config writes are not implemented in the Java port");
        }
        Object rawConfig = body != null ? body.get("config") : null;
        if (!(rawConfig instanceof Map<?, ?> incomingRaw)) {
            return status(HttpStatus.BAD_REQUEST, "config must be a mapping");
        }
        try {
            Map<String, Object> existing = profileService.readConfig(profile.profile());
            Map<String, Object> incoming = toStringKeyMap(incomingRaw);
            profileService.writeConfig(profile.profile(), deepMerge(existing, incoming));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write profile config");
        }
    }

    @GetMapping({"/api/env", "/p/{profile}/api/env"})
    @Operation(summary = "Return known env var key status without secret values")
    public ResponseEntity<Map<String, Object>> env(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null, "env");
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            return ResponseEntity.ok(envRows(profile.profile()));
        } catch (IOException e) {
            return status(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read profile env status");
        }
    }

    @PutMapping({"/api/env", "/p/{profile}/api/env"})
    @Operation(summary = "Reject dashboard env writes not supported by Java port")
    public ResponseEntity<Map<String, Object>> setEnv(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "env");
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("dashboard env writes are not implemented in the Java port");
    }

    @DeleteMapping({"/api/env", "/p/{profile}/api/env"})
    @Operation(summary = "Reject dashboard env deletes not supported by Java port")
    public ResponseEntity<Map<String, Object>> deleteEnv(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "env");
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("dashboard env deletes are not implemented in the Java port");
    }

    @PostMapping({"/api/env/reveal", "/p/{profile}/api/env/reveal"})
    @Operation(summary = "Reject dashboard env reveal to avoid exposing secrets")
    public ResponseEntity<Map<String, Object>> revealEnv(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "env");
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("dashboard env reveal is not implemented in the Java port");
    }

    @GetMapping("/api/credentials/pool")
    @Operation(summary = "Return configured credential summaries without secret values")
    public Map<String, Object> credentialPool() {
        String apiKey = properties.getModel().getApiKey();
        if (!hasText(apiKey)) {
            return Map.of("providers", List.of());
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", 1);
        entry.put("id", null);
        entry.put("label", "Configured model API key");
        entry.put("auth_type", "api_key");
        entry.put("source", "application");
        entry.put("priority", 0);
        entry.put("last_status", null);
        entry.put("request_count", 0);
        entry.put("token_preview", redact(apiKey));
        entry.put("has_refresh", false);

        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("provider", defaultIfBlank(properties.getModel().getProvider(), "openai-compatible"));
        provider.put("entries", List.of(entry));
        return Map.of("providers", List.of(provider));
    }

    @PostMapping("/api/credentials/pool")
    @Operation(summary = "Reject dashboard credential pool writes not supported by Java port")
    public ResponseEntity<Map<String, Object>> addCredentialPoolEntry(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String provider = body != null && body.get("provider") instanceof String rawProvider ? rawProvider : "";
        String apiKey = body != null && body.get("api_key") instanceof String rawApiKey ? rawApiKey : "";
        if (!hasText(provider) || !hasText(apiKey)) {
            return status(HttpStatus.BAD_REQUEST, "provider and api_key are required");
        }
        return notImplemented("credential pool writes are not implemented in the Java port");
    }

    @DeleteMapping("/api/credentials/pool/{provider}/{index}")
    @Operation(summary = "Reject dashboard credential pool deletion not supported by Java port")
    public ResponseEntity<Map<String, Object>> removeCredentialPoolEntry(
        @PathVariable String provider,
        @PathVariable int index
    ) {
        return notImplemented("credential pool deletion is not implemented in the Java port");
    }

    @PostMapping("/api/providers/validate")
    @Operation(summary = "Return safe provider credential validation fallback")
    public Map<String, Object> validateProvider(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return Map.of(
            "ok", false,
            "reachable", false,
            "message", "provider credential validation is not implemented in the Java port",
            "models", List.of());
    }

    @GetMapping("/api/providers/custom-endpoints")
    @Operation(summary = "Return empty custom endpoint catalog with current Java runtime selection")
    public Map<String, Object> customEndpoints() {
        RuntimeConfigService.RuntimeModelSelection selection = runtimeConfigService.getModelSelection();
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("provider", selection != null && selection.provider() != null
            ? selection.provider()
            : defaultIfBlank(properties.getModel().getProvider(), "openai-compatible"));
        current.put("model", selection != null && selection.model() != null
            ? selection.model()
            : defaultIfBlank(properties.getModel().getModelName(), ""));
        current.put("base_url", selection != null && selection.baseUrl() != null
            ? selection.baseUrl()
            : defaultIfBlank(properties.getModel().getBaseUrl(), ""));
        return Map.of(
            "current", current,
            "endpoints", List.of());
    }

    @PostMapping("/api/providers/custom-endpoints")
    public ResponseEntity<Map<String, Object>> saveCustomEndpoint(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String name = bodyString(body, "name");
        String baseUrl = bodyString(body, "base_url");
        String model = bodyString(body, "model");
        if (!hasText(name)) {
            return status(HttpStatus.BAD_REQUEST, "name required");
        }
        if (!hasText(baseUrl)) {
            return status(HttpStatus.BAD_REQUEST, "base_url required");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return status(HttpStatus.BAD_REQUEST, "base_url must include scheme and host");
            }
        } catch (IllegalArgumentException e) {
            return status(HttpStatus.BAD_REQUEST, "base_url must include scheme and host");
        }
        if (!hasText(model)) {
            return status(HttpStatus.BAD_REQUEST, "model required");
        }
        return notImplemented("custom endpoint persistence is not implemented in the Java port");
    }

    @PostMapping("/api/providers/custom-endpoints/validate")
    public Map<String, Object> validateCustomEndpoint(
        @RequestBody(required = false) CustomEndpointBody body
    ) {
        String baseUrl = body != null ? clean(body.baseUrl()) : null;
        String model = body != null ? clean(body.model()) : null;
        if (baseUrl == null || model == null) {
            return validation(false, "base_url and model are required");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return validation(false, "base_url must be an absolute URL");
            }
            return Map.of(
                "ok", true,
                "reachable", false,
                "message", "URL shape is valid; live endpoint probing is not implemented in the Java port",
                "models", List.of(model));
        } catch (IllegalArgumentException e) {
            return validation(false, "base_url is invalid");
        }
    }

    @PostMapping("/api/providers/custom-endpoints/{endpointId}/activate")
    public ResponseEntity<Map<String, Object>> activateCustomEndpoint(@PathVariable String endpointId) {
        return status(HttpStatus.NOT_FOUND, "custom endpoint not found");
    }

    @DeleteMapping("/api/providers/custom-endpoints/{endpointId}")
    public ResponseEntity<Map<String, Object>> deleteCustomEndpoint(@PathVariable String endpointId) {
        return status(HttpStatus.NOT_FOUND, "custom endpoint not found");
    }

    @GetMapping("/api/providers/oauth")
    public Map<String, Object> oauthProviders() {
        return Map.of("providers", List.of());
    }

    @DeleteMapping("/api/providers/oauth/{providerId}")
    public ResponseEntity<Map<String, Object>> disconnectOauth(@PathVariable String providerId) {
        return notImplemented("OAuth provider disconnect is not implemented in the Java port");
    }

    @PostMapping("/api/providers/oauth/{providerId}/start")
    public ResponseEntity<Map<String, Object>> startOauth(@PathVariable String providerId) {
        return notImplemented("OAuth login is not implemented in the Java port");
    }

    @PostMapping("/api/providers/oauth/{providerId}/submit")
    public ResponseEntity<Map<String, Object>> submitOauth(
        @PathVariable String providerId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("OAuth login is not implemented in the Java port");
    }

    @GetMapping("/api/providers/oauth/{providerId}/poll/{sessionId}")
    public ResponseEntity<Map<String, Object>> pollOauth(
        @PathVariable String providerId,
        @PathVariable String sessionId
    ) {
        return notImplemented("OAuth login is not implemented in the Java port");
    }

    @DeleteMapping("/api/providers/oauth/sessions/{sessionId}")
    public Map<String, Object> cancelOauth(@PathVariable String sessionId) {
        return Map.of("ok", true);
    }

    private Map<String, Object> sanitizedConfig(String profile, boolean includeRuntimeSelection) throws IOException {
        if (isDefaultProfile(profile)) {
            return sanitizedGlobalConfig(includeRuntimeSelection);
        }
        if (profileService == null) {
            throw new java.io.FileNotFoundException("profile-scoped config is not available");
        }
        Map<String, Object> defaults = sanitizedGlobalConfig(false);
        Map<String, Object> raw = profileService.readConfig(profile);
        Map<String, Object> merged = deepMerge(defaults, publicConfigCopy(raw));
        normalizeModelSection(merged);
        return merged;
    }

    private Map<String, Object> sanitizedGlobalConfig(boolean includeRuntimeSelection) {
        RuntimeConfigService.RuntimeModelSelection selection = includeRuntimeSelection
            ? runtimeConfigService.getModelSelection()
            : null;

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("provider", selection != null && selection.provider() != null
            ? selection.provider()
            : defaultIfBlank(properties.getModel().getProvider(), "openai-compatible"));
        model.put("default", selection != null && selection.model() != null
            ? selection.model()
            : defaultIfBlank(properties.getModel().getModelName(), ""));
        model.put("name", model.get("default"));
        model.put("base_url", selection != null && selection.baseUrl() != null
            ? selection.baseUrl()
            : defaultIfBlank(properties.getModel().getBaseUrl(), ""));
        model.put("max_tokens", properties.getModel().getMaxTokens());
        model.put("timeout_seconds", properties.getModel().getTimeoutSeconds());

        Map<String, Object> web = new LinkedHashMap<>();
        web.put("search_provider", properties.getWeb().getSearchProvider());
        web.put("search_results", properties.getWeb().getSearchResults());
        web.put("extract_timeout_seconds", properties.getWeb().getExtractTimeoutSeconds());

        Map<String, Object> tts = new LinkedHashMap<>();
        tts.put("provider", properties.getTts().getProvider());
        tts.put("model", properties.getTts().getModel());

        Map<String, Object> stt = new LinkedHashMap<>();
        stt.put("enabled", properties.getTranscription().isEnabled());
        stt.put("provider", properties.getTranscription().getProvider());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("model", model);
        config.put("web", web);
        config.put("tts", tts);
        config.put("stt", stt);
        config.put("skills", Map.of("default_toolsets", properties.getSkills().getDefaultToolsets()));
        config.put("terminal", Map.of("backend", "local"));
        return config;
    }

    private Map<String, Object> publicConfigCopy(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        raw.forEach((key, value) -> {
            if (hasText(key) && !key.startsWith("_")) {
                result.put(key, publicConfigValue(key, value));
            }
        });
        return result;
    }

    private Object publicConfigValue(String key, Object value) {
        if (sensitiveConfigKey(key)) {
            return "<redacted>";
        }
        if (value instanceof Map<?, ?> map) {
            return publicConfigCopy(toStringKeyMap(map));
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(item -> item instanceof Map<?, ?> map ? publicConfigCopy(toStringKeyMap(map)) : item)
                .toList();
        }
        return value;
    }

    private static boolean sensitiveConfigKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return normalized.contains("api_key")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("password")
            || normalized.contains("credential")
            || normalized.contains("private_key");
    }

    private Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> incoming) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (base != null) {
            base.forEach((key, value) -> result.put(key, cloneConfigValue(value)));
        }
        if (incoming == null) {
            return result;
        }
        incoming.forEach((key, value) -> {
            Object existing = result.get(key);
            if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> incomingMap) {
                result.put(key, deepMerge(toStringKeyMap(existingMap), toStringKeyMap(incomingMap)));
            } else {
                result.put(key, cloneConfigValue(value));
            }
        });
        return result;
    }

    private Object cloneConfigValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::cloneConfigValue).toList();
        }
        return value;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), cloneConfigValue(value));
            }
        });
        return result;
    }

    private void normalizeModelSection(Map<String, Object> config) {
        Object rawModel = config.get("model");
        Map<String, Object> baseModel = toStringKeyMap((Map<?, ?>) sanitizedGlobalConfig(false).get("model"));
        Map<String, Object> model;
        if (rawModel instanceof Map<?, ?> modelMap) {
            model = deepMerge(baseModel, toStringKeyMap(modelMap));
        } else if (rawModel != null) {
            model = baseModel;
            model.put("default", String.valueOf(rawModel));
        } else {
            model = baseModel;
        }
        Object configured = firstPresent(model.get("default"), model.get("model"), model.get("name"));
        if (configured != null) {
            model.put("default", configured);
            model.put("name", configured);
        }
        config.put("model", model);
    }

    private String dashboardPreference(String profile, String key, String fallback) throws IOException {
        if (isDefaultProfile(profile)) {
            return fallback;
        }
        Object dashboard = profileService.readConfig(profile).get("dashboard");
        if (dashboard instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof String text && hasText(text)) {
                return text.trim();
            }
        }
        return fallback;
    }

    private void writeDashboardPreference(String profile, String key, String value) throws IOException {
        Map<String, Object> config = profileService.readConfig(profile);
        Map<String, Object> dashboard = config.get("dashboard") instanceof Map<?, ?> map
            ? toStringKeyMap(map)
            : new LinkedHashMap<>();
        dashboard.put(key, value);
        config.put("dashboard", dashboard);
        profileService.writeConfig(profile, config);
    }

    private Map<String, Object> envRows(String profile) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        if (isDefaultProfile(profile)) {
            envRow(response, "AGENT_MODEL_API_KEY", "Main model API key", "model",
                hasText(properties.getModel().getApiKey()));
            envRow(response, "AGENT_MODEL_BASE_URL", "Main model base URL", "model",
                hasText(properties.getModel().getBaseUrl()));
            envRow(response, "AGENT_WEB_SEARXNG_URL", "SearXNG URL", "tools",
                hasText(properties.getWeb().getSearxngUrl()));
            envRow(response, "AGENT_TTS_API_KEY", "OpenAI TTS API key", "tools",
                hasText(properties.getTts().getApiKey()));
            envRow(response, "AGENT_IMAGE_GEN_API_KEY", "Image generation API key", "tools",
                hasText(properties.getImageGen().getApiKey()));
            envRow(response, "AGENT_VISION_API_KEY", "Vision API key", "tools",
                hasText(properties.getVision().getApiKey()));
            envRow(response, "AGENT_TRANSCRIPTION_API_KEY", "Transcription API key", "tools",
                hasText(properties.getTranscription().getApiKey()));
            return response;
        }

        Map<String, Object> config = profileService.readConfig(profile);
        envRow(response, "AGENT_MODEL_API_KEY", "Main model API key", "model",
            hasNestedText(config, "model", "api_key"));
        envRow(response, "AGENT_MODEL_BASE_URL", "Main model base URL", "model",
            hasNestedText(config, "model", "base_url"));
        envRow(response, "AGENT_WEB_SEARXNG_URL", "SearXNG URL", "tools",
            hasNestedText(config, "web", "searxng_url"));
        envRow(response, "AGENT_TTS_API_KEY", "OpenAI TTS API key", "tools",
            hasNestedText(config, "tts", "api_key"));
        envRow(response, "AGENT_IMAGE_GEN_API_KEY", "Image generation API key", "tools",
            hasNestedText(config, "image_gen", "api_key"));
        envRow(response, "AGENT_VISION_API_KEY", "Vision API key", "tools",
            hasNestedText(config, "vision", "api_key"));
        envRow(response, "AGENT_TRANSCRIPTION_API_KEY", "Transcription API key", "tools",
            hasNestedText(config, "transcription", "api_key") || hasNestedText(config, "stt", "api_key"));
        return response;
    }

    private void envRow(Map<String, Object> target,
                        String key,
                        String description,
                        String category,
                        boolean propertySet) {
        String envValue = System.getenv(key);
        boolean isSet = hasText(envValue) || propertySet;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("is_set", isSet);
        row.put("redacted_value", isSet ? redact(envValue) : null);
        row.put("description", description);
        row.put("url", null);
        row.put("category", category);
        row.put("is_password", key.endsWith("_API_KEY") || key.endsWith("_KEY"));
        row.put("tools", List.of());
        row.put("advanced", false);
        row.put("channel_managed", false);
        row.put("provider", "");
        row.put("provider_label", "");
        row.put("custom", false);
        target.put(key, row);
    }

    private static Map<String, Object> field(String description, String type, String category) {
        return field(description, type, category, List.of());
    }

    private static Map<String, Object> field(String description,
                                             String type,
                                             String category,
                                             List<String> options) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", description);
        response.put("type", type);
        response.put("category", category);
        if (!options.isEmpty()) {
            response.put("options", options);
        }
        return response;
    }

    private static Map<String, Object> validation(boolean ok, String message) {
        return Map.of(
            "ok", ok,
            "reachable", false,
            "message", message,
            "models", List.of());
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static Optional<Map<String, Object>> diskStats() {
        Path target = firstExistingParent(hermesHome());
        if (target == null) {
            return Optional.empty();
        }
        try {
            FileStore store = Files.getFileStore(target);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long used = Math.max(0L, total - free);
            return Optional.of(Map.of(
                "total", total,
                "used", used,
                "free", free,
                "percent", total > 0 ? (used * 100.0d / total) : 0.0d));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static Path firstExistingParent(Path path) {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Map<String, Object> checkpointSession(Path child) {
        long[] stats = new long[2];
        try (Stream<Path> walk = Files.walk(child)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                try {
                    stats[0]++;
                    stats[1] += Files.size(file);
                } catch (IOException | SecurityException ignored) {
                    // Keep the read-only dashboard endpoint best-effort.
                }
            });
        } catch (IOException | SecurityException ignored) {
            // Keep the read-only dashboard endpoint best-effort.
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("session", child.getFileName().toString());
        response.put("files", stats[0]);
        response.put("bytes", stats[1]);
        return response;
    }

    private static List<Map<String, Object>> builtInDashboardThemes() {
        return List.of(
            theme("default", "Hermes Teal", "Classic dark teal - the canonical Hermes look"),
            theme("default-large", "Hermes Teal (Large)", "Hermes Teal with bigger fonts and roomier spacing"),
            theme("nous-blue", "Nous Blue", "Light mode - vivid Nous-blue accents on cream canvas"),
            theme("midnight", "Midnight", "Deep blue-violet with cool accents"),
            theme("ember", "Ember", "Warm crimson and bronze"),
            theme("mono", "Mono", "Clean grayscale - minimal and focused"),
            theme("cyberpunk", "Cyberpunk", "Neon green on black - matrix terminal"),
            theme("rose", "Rose", "Soft pink and warm ivory - easy on the eyes"));
    }

    private static Map<String, Object> theme(String name, String label, String description) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", name);
        response.put("label", label);
        response.put("description", description);
        return response;
    }

    private String rawConfigYaml(String profile) throws IOException {
        if (!isDefaultProfile(profile) && profileService != null) {
            return profileService.readRawConfig(profile);
        }
        return String.join("\n",
            "model:",
            "  provider: " + yamlQuote(defaultIfBlank(properties.getModel().getProvider(), "openai-compatible")),
            "  default: " + yamlQuote(defaultIfBlank(properties.getModel().getModelName(), "")),
            "  base_url: " + yamlQuote(defaultIfBlank(properties.getModel().getBaseUrl(), "")),
            "web:",
            "  search_provider: " + yamlQuote(defaultIfBlank(properties.getWeb().getSearchProvider(), "")),
            "tts:",
            "  provider: " + yamlQuote(defaultIfBlank(properties.getTts().getProvider(), "")),
            "stt:",
            "  enabled: " + properties.getTranscription().isEnabled(),
            "");
    }

    private String rawConfigPath(String profile) throws IOException {
        if (!isDefaultProfile(profile) && profileService != null) {
            return profileService.configPath(profile).toString();
        }
        return "classpath:application.yml";
    }

    private static String yamlQuote(String value) {
        return "'" + defaultIfBlank(value, "").replace("'", "''") + "'";
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> status(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> validateLoopbackRedirectUri(String raw) {
        if (!hasText(raw)) {
            return status(HttpStatus.BAD_REQUEST, "redirect_uri required");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return status(HttpStatus.BAD_REQUEST, "native redirect_uri must be http:// on the loopback interface");
        }
        if (!"http".equals(uri.getScheme())) {
            return status(HttpStatus.BAD_REQUEST, "native redirect_uri must be http:// on the loopback interface");
        }
        String host = uri.getHost();
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            return status(
                HttpStatus.BAD_REQUEST,
                "native redirect_uri host must be a loopback IP literal (127.0.0.1 / ::1)");
        }
        return null;
    }

    private static Map<String, Object> probeGhAuth() {
        try {
            Process process = new ProcessBuilder("gh", "auth", "status")
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("available", true, "authenticated", false);
            }
            return Map.of("available", true, "authenticated", process.exitValue() == 0);
        } catch (java.io.IOException e) {
            return Map.of("available", false, "authenticated", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("available", true, "authenticated", false);
        }
    }

    private static String redact(String value) {
        if (!hasText(value)) {
            return "<redacted>";
        }
        String clean = value.trim();
        if (clean.length() <= 8) {
            return "***";
        }
        return clean.substring(0, 4) + "..." + clean.substring(clean.length() - 4);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasNestedText(Map<String, Object> config, String section, String key) {
        Object rawSection = config != null ? config.get(section) : null;
        if (!(rawSection instanceof Map<?, ?> map)) {
            return false;
        }
        Object value = map.get(key);
        return value != null && hasText(String.valueOf(value));
    }

    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value instanceof String text) {
                if (hasText(text)) {
                    return text.trim();
                }
            } else if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean isDefaultProfile(String profile) {
        return profile == null || "default".equals(profile);
    }

    private String rawConfigBodyText(Map<String, Object> body) {
        Object raw = body != null && body.containsKey("yaml_text") ? body.get("yaml_text") : null;
        if (raw == null && body != null) {
            raw = body.get("yaml");
        }
        return raw != null ? String.valueOf(raw) : "";
    }

    private ProfileResolution resolveProfileScope(String pathProfile,
                                                  String queryProfile,
                                                  String bodyProfile,
                                                  String feature) {
        List<ResolvedProfile> resolved = new ArrayList<>();
        for (String raw : new String[] {pathProfile, queryProfile, bodyProfile}) {
            if (hasText(raw)) {
                ProfileResolution profile = normalizeProfile(raw, feature);
                if (profile.error() != null) {
                    return profile;
                }
                resolved.add(new ResolvedProfile(raw, profile.profile()));
            }
        }
        String profile = resolved.isEmpty() ? "default" : resolved.get(0).profile();
        for (ResolvedProfile item : resolved) {
            if (!profile.equals(item.profile())) {
                return ProfileResolution.error(status(HttpStatus.BAD_REQUEST, "profile values do not match"));
            }
        }
        if (!isDefaultProfile(profile) && profileService == null) {
            return ProfileResolution.error(notImplemented(
                "profile-scoped " + feature + " is not available in this Java agent configuration"));
        }
        if (profileService != null && !profileService.knownProfile(profile)) {
            return ProfileResolution.error(status(HttpStatus.NOT_FOUND, "Unknown profile: " + profile));
        }
        return ProfileResolution.ok(profile);
    }

    private ProfileResolution normalizeProfile(String rawProfile, String feature) {
        try {
            String profile = profileService != null
                ? profileService.normalizeProfileName(rawProfile)
                : rawProfile.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(profile)) {
                return ProfileResolution.error(status(HttpStatus.BAD_REQUEST,
                    "profile=all is not supported for " + feature));
            }
            if (profileService != null) {
                profileService.validateProfileName(profile);
            } else if (!isDefaultProfile(profile)) {
                return ProfileResolution.error(notImplemented(
                    "profile-scoped " + feature + " is not available in this Java agent configuration"));
            }
            return ProfileResolution.ok(profile);
        } catch (IllegalArgumentException e) {
            return ProfileResolution.error(status(HttpStatus.BAD_REQUEST, e.getMessage()));
        }
    }

    private static String bodyString(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value instanceof String text ? text.trim() : "";
    }

    private static String defaultIfBlank(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static Path hermesHome() {
        String prop = System.getProperty("hermes.home");
        if (hasText(prop)) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        String env = System.getenv("HERMES_HOME");
        if (hasText(env)) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }

    private static Path hermesRootHome() {
        Path home = hermesHome();
        Path fileName = home.getFileName();
        Path parent = home.getParent();
        if (fileName != null && parent != null && "profiles".equals(parent.getFileName() != null
            ? parent.getFileName().toString()
            : "")) {
            Path root = parent.getParent();
            if (root != null) {
                return root.toAbsolutePath().normalize();
            }
        }
        return home;
    }

    private static String installId() {
        String cached = installIdCache;
        if (cached != null) {
            return cached;
        }
        synchronized (INSTALL_ID_LOCK) {
            if (installIdCache != null) {
                return installIdCache;
            }
            Path path = hermesRootHome().resolve("install_id");
            try {
                if (Files.isRegularFile(path)) {
                    String stored = Files.readString(path).trim();
                    if (stored.matches("[0-9a-f]{32}")) {
                        installIdCache = stored;
                        return stored;
                    }
                }
                byte[] bytes = new byte[16];
                INSTALL_ID_RANDOM.nextBytes(bytes);
                String generated = toHex(bytes);
                Files.createDirectories(path.getParent());
                Files.writeString(path, generated + System.lineSeparator());
                installIdCache = generated;
                return generated;
            } catch (RuntimeException | IOException e) {
                return null;
            }
        }
    }

    static void clearInstallIdCacheForTests() {
        installIdCache = null;
    }

    private record ResolvedProfile(String raw, String profile) {
    }

    private record ProfileResolution(String profile, ResponseEntity<Map<String, Object>> error) {
        static ProfileResolution ok(String profile) {
            return new ProfileResolution(profile, null);
        }

        static ProfileResolution error(ResponseEntity<Map<String, Object>> error) {
            return new ProfileResolution(null, error);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    private static String implementationVersion() {
        String version = DashboardSystemController.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private record CustomEndpointBody(
        @com.fasterxml.jackson.annotation.JsonProperty("base_url")
        @com.fasterxml.jackson.annotation.JsonAlias("baseUrl")
        String baseUrl,
        String model
    ) {
    }

    private record PasswordLoginBody(String provider, String username, String password, String next) {
    }

    private record NativeTokenBody(
        String code,
        @com.fasterxml.jackson.annotation.JsonProperty("code_verifier")
        @com.fasterxml.jackson.annotation.JsonAlias("codeVerifier")
        String codeVerifier
    ) {
    }

    private record NativeRefreshBody(
        @com.fasterxml.jackson.annotation.JsonProperty("refresh_token")
        @com.fasterxml.jackson.annotation.JsonAlias("refreshToken")
        String refreshToken,
        String provider
    ) {
    }

    private record ThemeSetBody(String name, String profile) {
    }

    private record FontSetBody(String font, String profile) {
    }
}
