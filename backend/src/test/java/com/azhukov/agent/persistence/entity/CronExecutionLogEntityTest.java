package com.azhukov.agent.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h72: Tests for CronExecutionLogEntity.
 */
class CronExecutionLogEntityTest {

    @Test
    void constructor_setsAllFields() {
        UUID jobId = UUID.randomUUID();
        Instant started = Instant.now();
        Instant finished = started.plusSeconds(5);
        var entity = CronExecutionLogEntity.create(jobId, started, finished, "success", null);

        assertThat(entity.getJobId()).isEqualTo(jobId);
        assertThat(entity.getStartedAt()).isEqualTo(started);
        assertThat(entity.getFinishedAt()).isEqualTo(finished);
        assertThat(entity.getStatus()).isEqualTo("success");
        assertThat(entity.getErrorMessage()).isNull();
    }

    @Test
    void constructor_withError() {
        UUID jobId = UUID.randomUUID();
        Instant started = Instant.now();
        Instant finished = started.plusSeconds(10);
        var entity = CronExecutionLogEntity.create(jobId, started, finished, "failure", "Connection refused");

        assertThat(entity.getJobId()).isEqualTo(jobId);
        assertThat(entity.getStatus()).isEqualTo("failure");
        assertThat(entity.getErrorMessage()).isEqualTo("Connection refused");
    }

    @Test
    void constructor_withTimeout() {
        UUID jobId = UUID.randomUUID();
        Instant started = Instant.now();
        Instant finished = started.plusSeconds(120);
        var entity = CronExecutionLogEntity.create(jobId, started, finished, "timeout", "Execution timed out after 120s");

        assertThat(entity.getStatus()).isEqualTo("timeout");
        assertThat(entity.getErrorMessage()).contains("timed out");
    }

    @Test
    void defaultConstructor_createsEmptyEntity() {
        var entity = new CronExecutionLogEntity();
        assertThat(entity.getId()).isNull();
        assertThat(entity.getJobId()).isNull();
        assertThat(entity.getStatus()).isNull();
    }
}