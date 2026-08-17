package com.azhukov.agent.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h71/h74: Tests for CronJobEntity status tracking fields.
 */
class CronJobEntityStatusTest {

    @Test
    void lastStatus_canBeSetAndRetrieved() {
        var entity = new CronJobEntity();
        entity.setLastStatus("error");
        assertThat(entity.getLastStatus()).isEqualTo("error");
    }

    @Test
    void lastStatus_canBeCleared() {
        var entity = new CronJobEntity();
        entity.setLastStatus("error");
        entity.setLastStatus(null);
        assertThat(entity.getLastStatus()).isNull();
    }

    @Test
    void lastError_canBeSetAndRetrieved() {
        var entity = new CronJobEntity();
        String error = "Connection refused to backend";
        entity.setLastError(error);
        assertThat(entity.getLastError()).isEqualTo(error);
    }

    @Test
    void lastErrorAt_canBeSetAndRetrieved() {
        var entity = new CronJobEntity();
        Instant now = Instant.now();
        entity.setLastErrorAt(now);
        assertThat(entity.getLastErrorAt()).isEqualTo(now);
    }

    @Test
    void consecutiveFailures_defaultsToZero() {
        var entity = new CronJobEntity();
        assertThat(entity.getConsecutiveFailures()).isZero();
    }

    @Test
    void consecutiveFailures_canBeIncremented() {
        var entity = new CronJobEntity();
        entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
        entity.setConsecutiveFailures(entity.getConsecutiveFailures() + 1);
        assertThat(entity.getConsecutiveFailures()).isEqualTo(2);
    }

    @Test
    void consecutiveFailures_canBeReset() {
        var entity = new CronJobEntity();
        entity.setConsecutiveFailures(5);
        entity.setConsecutiveFailures(0);
        assertThat(entity.getConsecutiveFailures()).isZero();
    }
}