package com.azhukov.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.repository.DelegatedTaskRunRepository;

/**
 * Stalled-run monitor (docs/35 WP-1 item 8): running delegated runs without
 * fresh progress receive a stalled diagnostic event. Never auto-fails a run;
 * the timeout policy stays a separate explicit decision.
 */
@Service
public class StalledRunMonitor {

    private static final Logger log = LoggerFactory.getLogger(StalledRunMonitor.class);

    private final DelegatedTaskRunRepository repository;
    private final StalledRunPolicy policy;

    public StalledRunMonitor(
        DelegatedTaskRunRepository repository,
        @Value("${agent.delegate.stalled-after-seconds:900}") long stalledAfterSeconds
    ) {
        this.repository = repository;
        this.policy = new StalledRunPolicy(Duration.ofSeconds(stalledAfterSeconds));
    }

    @Scheduled(fixedDelayString = "${agent.delegate.stalled-scan-millis:60000}")
    public void scan() {
        Instant now = Instant.now();
        List<DelegatedTaskRunEntity> running = repository.findByStatus("running");
        for (DelegatedTaskRunEntity run : running) {
            if (!policy.stalled(run.getStatus(), run.getStartedAt(), run.getLastProgressAt(), now)) {
                continue;
            }
            boolean emit = policy.shouldEmitDiagnostic(run.getStalledDiagnosticAt())
                || policy.shouldEmitDiagnosticAfterProgress(run.getStalledDiagnosticAt(), run.getLastProgressAt());
            if (!emit) {
                continue;
            }
            run.setStalledDiagnosticAt(now);
            repository.save(run);
            log.warn("delegated run {} stalled: no progress since {} (goal: {})", run.getId(),
                run.getLastProgressAt() != null ? run.getLastProgressAt() : run.getStartedAt(),
                run.getGoal());
        }
    }
}
