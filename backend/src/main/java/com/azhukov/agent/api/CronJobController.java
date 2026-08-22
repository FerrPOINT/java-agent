package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CronExecutionLogDto;
import com.azhukov.agent.api.dto.CronJobDto;
import com.azhukov.agent.api.mapper.CronJobDtoMapper;
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

    @PostMapping
    public CronJobDto create(@Valid @RequestBody CreateCronRequest request) {
        return cronJobDtoMapper.toDto(cronJobService.create(request.name(), request.schedule(), request.prompt(), request.deliverTo(), request.skills()));
    }

    @GetMapping
    public List<CronJobDto> list() {
        return cronJobDtoMapper.toDtoList(cronJobService.list());
    }

    @PutMapping("/{id}")
    public CronJobDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCronRequest request) {
        return cronJobDtoMapper.toDto(cronJobService.update(id, request.name(), request.schedule(), request.prompt(),
            request.deliverTo(), request.enabled()));
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
    public void delete(@PathVariable UUID id) {
        cronJobService.remove(id);
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
        String skills
    ) {}
    public record UpdateCronRequest(
        @jakarta.validation.constraints.NotBlank String name,
        @jakarta.validation.constraints.NotBlank String schedule,
        String prompt,
        String deliverTo,
        Boolean enabled
    ) {}
}