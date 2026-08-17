package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.service.CronJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/cron")
@RequiredArgsConstructor
@Slf4j
public class CronJobController {

    private final CronJobService cronJobService;
    private final CronExecutionLogRepository cronExecutionLogRepository;

    @PostMapping
    public CronJobEntity create(@Valid @RequestBody CreateCronRequest request) {
        return cronJobService.create(request.name(), request.schedule(), request.prompt(), request.deliverTo(), request.skills());
    }

    @GetMapping
    public List<CronJobEntity> list() {
        return cronJobService.list();
    }

    @PutMapping("/{id}")
    public CronJobEntity update(@PathVariable UUID id, @Valid @RequestBody UpdateCronRequest request) {
        return cronJobService.update(id, request.name(), request.schedule(), request.prompt(),
            request.deliverTo(), request.enabled());
    }

    @PostMapping("/{id}/pause")
    public CronJobEntity pause(@PathVariable UUID id) {
        return cronJobService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public CronJobEntity resume(@PathVariable UUID id) {
        return cronJobService.resume(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        cronJobService.remove(id);
    }

    @PostMapping("/{id}/run")
    public CronJobEntity runNow(@PathVariable UUID id) {
        return cronJobService.runNow(id);
    }

    // h72: Cron execution ledger — list execution history for a job.
    @GetMapping("/{id}/executions")
    public List<CronExecutionLogEntity> listExecutions(@PathVariable UUID id) {
        return cronExecutionLogRepository.findByJobIdOrderByStartedAtDesc(id);
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