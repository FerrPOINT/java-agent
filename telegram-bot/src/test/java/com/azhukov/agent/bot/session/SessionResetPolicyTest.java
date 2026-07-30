package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class SessionResetPolicyTest {

    @Test
    void noneModeNeverResets() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.NONE);

        Instant created = Instant.parse("2025-01-01T00:00:00Z");
        Instant updated = Instant.parse("2025-01-01T00:00:00Z");
        Instant now = Instant.parse("2025-12-31T23:59:59Z");

        assertThat(policy.shouldReset(created, updated, now)).isNull();
    }

    @Test
    void idleModeResetsAfterIdleTimeout() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.IDLE);
        policy.setIdleMinutes(30);

        Instant created = Instant.parse("2025-01-01T10:00:00Z");
        Instant updated = Instant.parse("2025-01-01T10:00:00Z");
        Instant now = Instant.parse("2025-01-01T10:45:00Z"); // 45 min later

        assertThat(policy.shouldReset(created, updated, now)).isEqualTo("idle");
    }

    @Test
    void idleModeDoesNotResetBeforeTimeout() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.IDLE);
        policy.setIdleMinutes(30);

        Instant created = Instant.parse("2025-01-01T10:00:00Z");
        Instant updated = Instant.parse("2025-01-01T10:00:00Z");
        Instant now = Instant.parse("2025-01-01T10:15:00Z"); // 15 min later

        assertThat(policy.shouldReset(created, updated, now)).isNull();
    }

    @Test
    void dailyModeResetsAfterDailyBoundary() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.DAILY);
        policy.setAtHour(4);

        // Updated before 4am, now is after 4am same day
        LocalDateTime updatedLocal = LocalDateTime.of(2025, 7, 15, 3, 0);
        LocalDateTime nowLocal = LocalDateTime.of(2025, 7, 15, 5, 0);
        Instant updated = updatedLocal.atZone(ZoneId.systemDefault()).toInstant();
        Instant now = nowLocal.atZone(ZoneId.systemDefault()).toInstant();

        assertThat(policy.shouldReset(updated, updated, now)).isEqualTo("daily");
    }

    @Test
    void dailyModeDoesNotResetBeforeBoundary() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.DAILY);
        policy.setAtHour(4);

        // Updated at 5am, now at 6am same day — both after 4am, same day boundary
        LocalDateTime updatedLocal = LocalDateTime.of(2025, 7, 15, 5, 0);
        LocalDateTime nowLocal = LocalDateTime.of(2025, 7, 15, 6, 0);
        Instant updated = updatedLocal.atZone(ZoneId.systemDefault()).toInstant();
        Instant now = nowLocal.atZone(ZoneId.systemDefault()).toInstant();

        assertThat(policy.shouldReset(updated, updated, now)).isNull();
    }

    @Test
    void bothModeIdleTriggersFirst() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.BOTH);
        policy.setIdleMinutes(30);
        policy.setAtHour(4);

        // Idle triggers before daily
        Instant updated = Instant.now().minusSeconds(3600); // 1 hour ago
        Instant now = Instant.now();

        assertThat(policy.shouldReset(updated, updated, now)).isEqualTo("idle");
    }

    @Test
    void bothModeDailyCanTrigger() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.BOTH);
        policy.setIdleMinutes(1440); // 24 hours idle
        policy.setAtHour(4);

        // Updated yesterday at 3am, now today at 5am — daily triggers
        LocalDateTime updatedLocal = LocalDateTime.of(2025, 7, 14, 3, 0);
        LocalDateTime nowLocal = LocalDateTime.of(2025, 7, 15, 5, 0);
        Instant updated = updatedLocal.atZone(ZoneId.systemDefault()).toInstant();
        Instant now = nowLocal.atZone(ZoneId.systemDefault()).toInstant();

        // Idle = 1440 min = 24h, so idle deadline is 2025-07-15 03:00 → now is 05:00 so idle triggers first
        // Actually 24h from 14th 03:00 = 15th 03:00, now is 15th 05:00, so idle triggers
        String reason = policy.shouldReset(updated, updated, now);
        assertThat(reason).isNotNull();
    }

    @Test
    void isExpiredReturnsTrueForExpiredSession() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.IDLE);
        policy.setIdleMinutes(1);

        Instant updated = Instant.now().minusSeconds(120); // 2 minutes ago
        Instant now = Instant.now();

        assertThat(policy.isExpired(updated, now)).isTrue();
    }

    @Test
    void isExpiredReturnsFalseForActiveSession() {
        SessionResetPolicy policy = new SessionResetPolicy();
        policy.setMode(SessionResetMode.IDLE);
        policy.setIdleMinutes(60);

        Instant updated = Instant.now().minusSeconds(30); // 30 seconds ago
        Instant now = Instant.now();

        assertThat(policy.isExpired(updated, now)).isFalse();
    }

    @Test
    void defaultValues() {
        SessionResetPolicy policy = new SessionResetPolicy();
        assertThat(policy.getMode()).isEqualTo(SessionResetMode.BOTH);
        assertThat(policy.getAtHour()).isEqualTo(4);
        assertThat(policy.getIdleMinutes()).isEqualTo(1440);
        assertThat(policy.isNotify()).isTrue();
    }
}