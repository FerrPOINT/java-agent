package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CronExecutionLogDto;
import com.azhukov.agent.api.dto.CronJobDto;
import com.azhukov.agent.api.mapper.CronJobDtoMapper;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.azhukov.agent.service.CronJobService;
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