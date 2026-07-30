package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
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

    @PostMapping
    public CronJobEntity create(@RequestBody CreateCronRequest request) {
        return cronJobService.create(request.name(), request.schedule(), request.prompt(), request.deliverTo(), request.skills());
    }

    @GetMapping
    public List<CronJobEntity> list() {
        return cronJobService.list();
    }

    @PutMapping("/{id}")
    public CronJobEntity update(@PathVariable UUID id, @RequestBody UpdateCronRequest request) {
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

    public record CreateCronRequest(String name, String schedule, String prompt, String deliverTo, String skills) {}
    public record UpdateCronRequest(String name, String schedule, String prompt, String deliverTo, Boolean enabled) {}
}