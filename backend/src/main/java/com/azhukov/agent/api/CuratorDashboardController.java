package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.CuratorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/curator")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Hermes-compatible", description = "Dashboard curator compatibility")
public class CuratorDashboardController {

    private final CuratorService curatorService;
    private final AtomicBoolean curatorRunInFlight = new AtomicBoolean(false);
    private final ExecutorService curatorRunExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dashboard-curator-run");
        thread.setDaemon(true);
        return thread;
    });

    @GetMapping
    public Map<String, Object> status(@RequestParam(name = "profile", required = false) String profile) {
        Map<String, Object> state = safeState();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", curatorService.isEnabled());
        body.put("paused", curatorService.isPaused());
        body.put("interval_hours", curatorService.getIntervalHours());
        body.put("last_run_at", state.get("last_run_at"));
        body.put("min_idle_hours", curatorService.getMinIdleHours());
        body.put("stale_after_days", curatorService.getStaleAfterDays());
        body.put("archive_after_days", curatorService.getArchiveAfterDays());
        return body;
    }

    @PutMapping("/paused")
    public ResponseEntity<Map<String, Object>> setPaused(@RequestBody(required = false) CuratorPause body) {
        if (body == null || body.paused() == null) {
            return badRequest("paused is required");
        }
        curatorService.setPaused(body.paused());
        return ResponseEntity.ok(Map.of("ok", true, "paused", body.paused()));
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runNow(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("pid", ProcessHandle.current().pid());
        response.put("name", "curator-run");

        if (!curatorRunInFlight.compareAndSet(false, true)) {
            response.put("already_running", true);
            return ResponseEntity.ok(response);
        }

        try {
            curatorRunExecutor.execute(() -> {
                try {
                    curatorService.runCycle();
                } catch (RuntimeException e) {
                    log.warn("Dashboard curator run failed: {}", e.getMessage(), e);
                } finally {
                    curatorRunInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            curatorRunInFlight.set(false);
            return ResponseEntity.status(HttpStatusCode.valueOf(500))
                .body(errorBody("Failed to run curator: " + e.getMessage()));
        }

        return ResponseEntity.ok(response);
    }

    @PreDestroy
    void shutdown() {
        curatorRunExecutor.shutdownNow();
    }

    private Map<String, Object> safeState() {
        try {
            Map<String, Object> state = curatorService.loadState();
            return state != null ? state : Map.of();
        } catch (RuntimeException e) {
            log.debug("Failed to read curator state: {}", e.getMessage());
            return Map.of();
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(errorBody(detail));
    }

    private static Map<String, Object> errorBody(String detail) {
        return Map.of("detail", detail, "error", detail);
    }

    private record CuratorPause(Boolean paused) {
    }
}
