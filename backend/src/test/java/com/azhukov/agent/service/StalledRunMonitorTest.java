package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure decision logic of the stalled-run monitor (docs/35 WP-1 item 8):
 * progress events coalesce and running runs without fresh progress get a
 * stalled diagnostic, never an automatic failure.
 */
class StalledRunMonitorTest {

    private final StalledRunPolicy policy = new StalledRunPolicy(Duration.ofMinutes(15));

    @Test
    void freshProgressIsNotStalled() {
        Instant now = Instant.now();
        assertThat(policy.stalled("running", now.minus(Duration.ofHours(3)), now.minus(Duration.ofMinutes(2)), now)).isFalse();
    }

    @Test
    void noProgressFallsBackToStartedAt() {
        Instant now = Instant.now();
        assertThat(policy.stalled("running", now.minus(Duration.ofMinutes(30)), null, now)).isTrue();
    }

    @Test
    void staleProgressIsStalled() {
        Instant now = Instant.now();
        assertThat(policy.stalled("running", now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(60)), now)).isTrue();
    }

    @Test
    void terminalRunsAreNeverStalled() {
        Instant now = Instant.now();
        for (String status : new String[]{"completed", "failed", "error", "timeout", "interrupted"}) {
            assertThat(policy.stalled(status, now.minus(Duration.ofDays(3)), null, now)).isFalse();
        }
    }

    @Test
    void diagnosticIsEmittedOnceUntilProgressResumes() {
        Instant now = Instant.now();
        assertThat(policy.shouldEmitDiagnostic(null)).isTrue();
        assertThat(policy.shouldEmitDiagnostic(now.minus(Duration.ofMinutes(5)))).isFalse();
        assertThat(policy.shouldEmitDiagnosticAfterProgress(now.minus(Duration.ofMinutes(90)), now.minus(Duration.ofMinutes(3)))).isTrue();
        assertThat(policy.shouldEmitDiagnosticAfterProgress(now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(30)))).isFalse();
    }
}
