package com.azhukov.agent.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure stalled-run decision core (docs/35 WP-1 item 8). Runs are never
 * auto-failed: staleness is a diagnostic, not a terminal transition.
 */
public final class StalledRunPolicy {

    private final Duration stalledAfter;

    public StalledRunPolicy(Duration stalledAfter) {
        this.stalledAfter = stalledAfter;
    }

    public boolean stalled(String status, Instant startedAt, Instant lastProgressAt, Instant now) {
        if (!"running".equals(status)) {
            return false;
        }
        Instant evidence = lastProgressAt != null ? lastProgressAt : startedAt;
        if (evidence == null) {
            return false;
        }
        return Duration.between(evidence, now).compareTo(stalledAfter) > 0;
    }

    public boolean shouldEmitDiagnostic(Instant stalledDiagnosticAt) {
        return stalledDiagnosticAt == null;
    }

    public boolean shouldEmitDiagnosticAfterProgress(Instant stalledDiagnosticAt, Instant lastProgressAt) {
        if (stalledDiagnosticAt == null || lastProgressAt == null) {
            return false;
        }
        return lastProgressAt.isAfter(stalledDiagnosticAt);
    }
}
