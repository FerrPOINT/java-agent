package com.azhukov.agent.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiRunService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AgentProperties properties;
    private OpenAiRunService openAiRunService;
    private ApiRunAdmissionService apiRunAdmissionService;
    private DataSource dataSource;

    @Autowired(required = false)
    void setOpenAiRunService(OpenAiRunService openAiRunService) {
        this.openAiRunService = openAiRunService;
    }

    @Autowired(required = false)
    void setApiRunAdmissionService(ApiRunAdmissionService apiRunAdmissionService) {
        this.apiRunAdmissionService = apiRunAdmissionService;
    }

    @Autowired(required = false)
    void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of(
            "status", "UP",
            "name", properties.getName()
        );
    }

    @GetMapping("/api/v1/agent/health")
    public Map<String, String> agentHealth() {
        return Map.of(
            "status", "UP",
            "name", properties.getName()
        );
    }

    @GetMapping("/api/health")
    public Map<String, Object> dashboardHealth() {
        return Map.of(
            "ok", true,
            "version", implementationVersion(),
            "auth_required", false
        );
    }

    @GetMapping({"/health", "/v1/health", "/p/{profile}/health", "/p/{profile}/v1/health"})
    public Map<String, Object> hermesHealth() {
        return Map.of(
            "status", "ok",
            "platform", "java-agent",
            "version", implementationVersion()
        );
    }

    @GetMapping({"/health/detailed", "/p/{profile}/health/detailed"})
    public Map<String, Object> hermesHealthDetailed() {
        int activeRuns = activeApiRuns();
        String gatewayState = "running";
        Map<String, Object> readiness = collectReadiness(gatewayState, activeRuns);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", readiness.get("status"));
        payload.put("readiness", readiness);
        payload.put("platform", "java-agent");
        payload.put("version", implementationVersion());
        payload.put("gateway_state", gatewayState);
        payload.put("platforms", Map.of());
        payload.put("active_agents", activeRuns);
        payload.put("gateway_busy", "running".equals(gatewayState) && activeRuns > 0);
        payload.put("gateway_drainable", "running".equals(gatewayState));
        payload.put("exit_reason", null);
        payload.put("updated_at", Instant.now().toString());
        payload.put("pid", ProcessHandle.current().pid());
        return payload;
    }

    private static String implementationVersion() {
        String version = HealthController.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private int activeApiRuns() {
        int runServiceCount = openAiRunService != null ? openAiRunService.activeRunCount() : 0;
        int admissionCount = apiRunAdmissionService != null ? apiRunAdmissionService.activeRunCount() : 0;
        return Math.max(runServiceCount, admissionCount);
    }

    private Map<String, Object> collectReadiness(String gatewayState, int activeRuns) {
        Map<String, Object> stateDb = probeStateDb();
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("state_db", stateDb);
        checks.put("session_store", probeSessionStore(stateDb));
        checks.put("config", check("ok"));
        checks.put("model", check(hasConfiguredModel() ? "ok" : "degraded"));
        checks.put("disk", probeDisk());
        checks.put("gateway", gatewayCheck(gatewayState));
        checks.put("background_queues", backgroundQueueCheck(activeRuns));

        String status = checks.values().stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .allMatch(item -> "ok".equals(item.get("status"))) ? "ok" : "degraded";
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("status", status);
        readiness.put("checks", checks);
        return readiness;
    }

    private Map<String, Object> probeStateDb() {
        if (dataSource == null) {
            return check("ok", "not configured");
        }
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1) ? check("ok") : check("degraded", "connection not valid");
        } catch (Exception e) {
            return check("degraded", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> probeSessionStore(Map<String, Object> stateDb) {
        return check("ok".equals(stateDb.get("status")) ? "ok" : "unavailable");
    }

    private Map<String, Object> probeDisk() {
        try {
            var path = java.nio.file.Path.of("").toAbsolutePath();
            var store = java.nio.file.Files.getFileStore(path);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            double usedPercent = total > 0 ? Math.round(((double) (total - free) / total) * 1000.0) / 10.0 : 0.0;
            return check(usedPercent >= 90.0 ? "degraded" : "ok", null,
                "used_percent", usedPercent,
                "free_bytes", free);
        } catch (Exception e) {
            return check("degraded", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> gatewayCheck(String gatewayState) {
        return check("running".equals(gatewayState) || "draining".equals(gatewayState) ? "ok" : "degraded", null,
            "state", gatewayState,
            "connected_platforms", 0,
            "platforms", 0);
    }

    private Map<String, Object> backgroundQueueCheck(int activeRuns) {
        return check("ok", null,
            "active_api_runs", Math.max(0, activeRuns),
            "process_completions", 0,
            "active_delegations", 0);
    }

    private boolean hasConfiguredModel() {
        return properties.getModel() != null
            && properties.getModel().getModelName() != null
            && !properties.getModel().getModelName().isBlank();
    }

    private static Map<String, Object> check(String status) {
        return check(status, null);
    }

    private static Map<String, Object> check(String status, String detail) {
        return check(status, detail, new Object[0]);
    }

    private static Map<String, Object> check(String status, String detail, Object... extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        if (detail != null && !detail.isBlank()) {
            result.put("detail", detail);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            result.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return result;
    }
}
