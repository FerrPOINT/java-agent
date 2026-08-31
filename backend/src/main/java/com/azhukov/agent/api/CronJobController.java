package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CronExecutionLogDto;
import com.azhukov.agent.api.dto.CronJobDto;
import com.azhukov.agent.api.mapper.CronJobDtoMapper;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.CronSuggestionService;
import com.azhukov.agent.service.HeartbeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/cron")
@RequiredArgsConstructor
@Slf4j
public class CronJobController {

    private final CronJobService cronJobService;
    private final CronSuggestionService cronSuggestionService;
    private final HeartbeatService heartbeatService;
    private final CronExecutionLogRepository cronExecutionLogRepository;
    private final CronJobDtoMapper cronJobDtoMapper;
    private final com.azhukov.agent.service.CronBlueprintService cronBlueprintService;

    @PostMapping
    public CronJobDto create(@Valid @RequestBody CreateCronRequest request) {
        return cronJobDtoMapper.toDto(cronJobService.create(
            UserContext.getUserId(),
            request.name(), request.schedule(), request.prompt(), request.deliverTo(),
            request.skills(), request.contextFrom(),
            null, null, false, request.enabledToolsets(), request.workdir(),
            null, null, null));
    }

    @GetMapping
    public List<CronJobDto> list() {
        return cronJobDtoMapper.toDtoList(cronJobService.list(UserContext.scopeUserId()));
    }

    @PutMapping("/{id}")
    public CronJobDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCronRequest request) {
        return cronJobDtoMapper.toDto(cronJobService.update(id, request.name(), request.schedule(),
            request.prompt(), request.deliverTo(), request.enabled(),
            null, request.contextFrom(),
            null, null, null,
            request.enabledToolsets(), request.workdir(),
            null, null, null));
    }

    // ── Suggestions (Hermes /suggestions parity) ──

    @GetMapping("/suggestions")
    public List<CronSuggestionService.SuggestionRecord> suggestions() {
        return cronSuggestionService.listPending();
    }

    @PostMapping("/suggestions/catalog")
    public java.util.Map<String, Object> seedCatalog() {
        int added = cronSuggestionService.seedCatalogSuggestions();
        return java.util.Map.of("added", added,
            "message", added > 0
                ? "Seeded " + added + " curated starter suggestions."
                : "Catalog already seeded — nothing new to add.");
    }

    @PostMapping("/suggestions/{id}/accept")
    public java.util.Map<String, Object> acceptSuggestion(@PathVariable String id) {
        var entity = cronSuggestionService.acceptSuggestion(id);
        if (entity == null) {
            return java.util.Map.of("accepted", false, "reason", "not found or already decided");
        }
        return java.util.Map.of("accepted", true,
            "jobId", entity.getId().toString(), "name", entity.getName());
    }

    @PostMapping("/suggestions/{id}/dismiss")
    public java.util.Map<String, Object> dismissSuggestion(@PathVariable String id) {
        boolean ok = cronSuggestionService.dismissSuggestion(id);
        return java.util.Map.of("dismissed", ok);
    }

    @PostMapping("/suggestions/clear")
    public java.util.Map<String, Object> clearSuggestions() {
        return java.util.Map.of("cleared", cronSuggestionService.clearAccepted());
    }

    // ── Heartbeat (Hermes /heartbeat parity) ──

    public record HeartbeatSetRequest(UUID sessionId, String prompt, Integer intervalSeconds, Integer maxTicks) {}

    @GetMapping("/heartbeat/{sessionId}")
    public java.util.Map<String, Object> heartbeatStatus(@PathVariable UUID sessionId) {
        HeartbeatService.HeartbeatState st = heartbeatService.get(sessionId);
        if (st == null) return java.util.Map.of("set", false);
        return java.util.Map.of(
            "set", true,
            "prompt", st.prompt(),
            "intervalSeconds", st.intervalSeconds(),
            "interval", HeartbeatService.formatInterval(st.intervalSeconds()),
            "status", st.status(),
            "fireCount", st.fireCount());
    }

    @PostMapping("/heartbeat")
    public java.util.Map<String, Object> heartbeatSet(@org.springframework.web.bind.annotation.RequestBody HeartbeatSetRequest request) {
        // /loop --times N reaches here with maxTicks
        if (request.sessionId() == null || request.prompt() == null || request.prompt().isBlank()
            || request.intervalSeconds() == null || request.intervalSeconds() < HeartbeatService.MIN_INTERVAL_SECONDS) {
            return java.util.Map.of("ok", false,
                "reason", "sessionId, prompt and intervalSeconds >= " + HeartbeatService.MIN_INTERVAL_SECONDS + " required");
        }
        HeartbeatService.HeartbeatState st = heartbeatService.set(request.sessionId(), request.prompt(), request.intervalSeconds(),
            request.maxTicks() != null ? request.maxTicks() : 0);
        return java.util.Map.of("ok", true, "message",
            "Heartbeat set (every " + HeartbeatService.formatInterval(st.intervalSeconds()) + "): " + st.prompt());
    }

    @PostMapping("/heartbeat/{sessionId}/pause")
    public java.util.Map<String, Object> heartbeatPause(@PathVariable UUID sessionId) {
        HeartbeatService.HeartbeatState st = heartbeatService.pause(sessionId);
        return st == null
            ? java.util.Map.of("ok", false, "reason", "no active heartbeat")
            : java.util.Map.of("ok", true, "message", "Heartbeat paused: " + st.prompt());
    }

    @PostMapping("/heartbeat/{sessionId}/resume")
    public java.util.Map<String, Object> heartbeatResume(@PathVariable UUID sessionId) {
        HeartbeatService.HeartbeatState st = heartbeatService.resume(sessionId);
        return st == null
            ? java.util.Map.of("ok", false, "reason", "no paused heartbeat")
            : java.util.Map.of("ok", true, "message",
                "Heartbeat resumed (every " + HeartbeatService.formatInterval(st.intervalSeconds()) + "): " + st.prompt());
    }

    /** Bot polls this to deliver heartbeat/loop results to the chat (PEEK — not destructive). */
    @GetMapping("/heartbeat/{sessionId}/result")
    public java.util.Map<String, Object> heartbeatResult(@PathVariable UUID sessionId) {
        String result = heartbeatService.peekLastFireResult(sessionId);
        return result == null
            ? java.util.Map.of("hasResult", false)
            : java.util.Map.of("hasResult", true, "result", result);
    }

    /** ACK after a successful chat send — drops the delivered result. */
    @PostMapping("/heartbeat/{sessionId}/result/ack")
    public java.util.Map<String, Object> heartbeatResultAck(@PathVariable UUID sessionId) {
        return java.util.Map.of("acked", heartbeatService.ackFireResult(sessionId));
    }

    /** Report a failed send attempt; after 5 the result is dropped as poison. */
    @PostMapping("/heartbeat/{sessionId}/result/nack")
    public java.util.Map<String, Object> heartbeatResultNack(@PathVariable UUID sessionId) {
        return java.util.Map.of("drop", heartbeatService.shouldDropUndeliverable(sessionId));
        // drop=true → caller must call ack to remove the poisoned result
    }

    @PostMapping("/heartbeat/{sessionId}/clear")
    public java.util.Map<String, Object> heartbeatClear(@PathVariable UUID sessionId) {
        return java.util.Map.of("ok", heartbeatService.clear(sessionId));
    }

    @PostMapping("/{id}/pause")
    public CronJobDto pause(@PathVariable UUID id) {
        return cronJobDtoMapper.toDto(cronJobService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public CronJobDto resume(@PathVariable UUID id) {
        return cronJobDtoMapper.toDto(cronJobService.resume(id));
    }

    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!cronJobService.exists(id)) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        cronJobService.remove(id);
        return org.springframework.http.ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/run")
    public CronJobDto runNow(@PathVariable UUID id) {
        return cronJobDtoMapper.toDto(cronJobService.runNow(id));
    }

    // h72: Cron execution ledger — list execution history for a job.
    @GetMapping("/{id}/executions")
    public List<CronExecutionLogDto> listExecutions(@PathVariable UUID id) {
        return cronJobDtoMapper.toExecutionLogDtoList(cronExecutionLogRepository.findByJobIdOrderByStartedAtDesc(id));
    }

    // h76: Delivery high-water mark — the bot-side poller calls this after delivering
    // a run's output so each run is delivered exactly once.
    @PostMapping("/{id}/delivered")
    public CronJobDto markDelivered(@PathVariable UUID id) {
        return cronJobDtoMapper.toDto(cronJobService.markDelivered(id));
    }

    public record CreateCronRequest(
        @jakarta.validation.constraints.NotBlank String name,
        @jakarta.validation.constraints.NotBlank String schedule,
        @jakarta.validation.constraints.NotBlank String prompt,
        String deliverTo,
        String skills,
        String contextFrom,
        String enabledToolsets,
        String workdir
    ) {}
    public record UpdateCronRequest(
        @jakarta.validation.constraints.NotBlank String name,
        @jakarta.validation.constraints.NotBlank String schedule,
        String prompt,
        String deliverTo,
        Boolean enabled,
        String contextFrom,
        String enabledToolsets,
        String workdir
    ) {}

    // ------------------------------------------------------------------
    // Blueprints (Hermes hermes_cli/blueprint_cmd.py parity): /blueprint
    // without args lists the catalog; /blueprint <key> slot=val … fills the
    // typed slots and creates the cron job directly. No agent turn — the
    // deterministic power-user shortcut.
    // ------------------------------------------------------------------

    @GetMapping("/blueprints")
    public java.util.Map<String, Object> listBlueprints() {
        var svc = cronBlueprintService;
        return java.util.Map.of(
            "blueprints", svc.listBlueprints().stream()
                .map(bp -> java.util.Map.of(
                    "key", bp.key(),
                    "title", bp.title(),
                    "description", bp.description(),
                    "category", bp.category(),
                    "slots", bp.slots().stream()
                        .map(s -> java.util.Map.of(
                            "name", s.name(),
                            "type", s.type(),
                            "label", s.label(),
                            "default", s.defaultValue() == null ? "" : s.defaultValue(),
                            "optional", s.optional(),
                            "help", s.help() == null ? "" : s.help()))
                        .toList()))
                .toList());
    }

    public record BlueprintFillRequest(java.util.Map<String, String> values) {}

    @PostMapping("/blueprints/{key}/create")
    public CronJobDto createFromBlueprint(@PathVariable String key,
                                          @RequestBody(required = false) BlueprintFillRequest body) {
        var bp = cronBlueprintService.getBlueprint(key)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown blueprint: " + key + ". GET /api/v1/agent/cron/blueprints for the catalog."));
        // fillBlueprintWithTime (not fillBlueprint): only the WithTime variant
        // decomposes the time slot into {hour}/{minute} and maps weekday
        // presets to {dow} — fillBlueprint leaves raw placeholders and the
        // resulting cron fails validation.
        var spec = cronBlueprintService.fillBlueprintWithTime(bp, body == null ? java.util.Map.of() : body.values());
        return cronJobDtoMapper.toDto(cronJobService.create(
            spec.name(), spec.schedule(), spec.prompt(), spec.deliverTo(), spec.skills()));
    }
}