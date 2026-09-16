package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-c (Hermes cron.retry_unreachable parity): classification and ladder
 * boundaries. The full execution path is exercised in
 * CronJobServiceUnreachableE2ETest; these are the pure predicates.
 */
class CronUnreachableRetryTest {

    @Test
    void transientNetworkErrorsAreClassifiedUnreachable() {
        assertThat(CronJobService.isUnreachableBeforeModel("Connection refused: /192.168.1.5:8090")).isTrue();
        assertThat(CronJobService.isUnreachableBeforeModel("Connection reset by peer")).isTrue();
        assertThat(CronJobService.isUnreachableBeforeModel("Connect timed out")).isTrue();
        assertThat(CronJobService.isUnreachableBeforeModel("UnknownHostException: api.example.com")).isTrue();
        assertThat(CronJobService.isUnreachableBeforeModel("No route to host")).isTrue();
        assertThat(CronJobService.isUnreachableBeforeModel("Network is unreachable")).isTrue();
    }

    @Test
    void modelLevelErrorsAreNotUnreachable() {
        // A model-side failure means tokens may have flowed (or the model
        // answered with an error): re-running could duplicate side effects.
        assertThat(CronJobService.isUnreachableBeforeModel("400 Bad Request: invalid model")).isFalse();
        assertThat(CronJobService.isUnreachableBeforeModel("tool execution failed: exit 1")).isFalse();
        assertThat(CronJobService.isUnreachableBeforeModel(null)).isFalse();
        assertThat(CronJobService.isUnreachableBeforeModel("")).isFalse();
    }

    @Test
    void classificationIsCaseInsensitive() {
        assertThat(CronJobService.isUnreachableBeforeModel(
            "CONNECTION REFUSED (uptime check)".toUpperCase(Locale.ROOT))).isTrue();
    }

    @Test
    void ladderIsBoundedToThreeSteps() throws Exception {
        // 5/15/30 minutes; a job beyond step 3 waits the regular period.
        Method ladder = CronJobService.class.getDeclaredMethod(
            "unreachableRetryLadderSeconds", com.azhukov.agent.persistence.entity.CronJobEntity.class);
        ladder.setAccessible(true);
        Constructor<CronJobService> ctor = null; // service instantiation is heavy; call via a tiny shim below
        // Use the static array through reflection instead:
        var field = CronJobService.class.getDeclaredField("UNREACHABLE_LADDER_SECONDS");
        field.setAccessible(true);
        long[] steps = (long[]) field.get(null);
        assertThat(steps).containsExactly(5 * 60L, 15 * 60L, 30 * 60L);
    }

    @Test
    void oneShotsAreNotRecurring() throws Exception {
        Method m = CronJobService.class.getDeclaredMethod("isRecurring",
            com.azhukov.agent.persistence.entity.CronJobEntity.class);
        m.setAccessible(true);
        var oneShot = new com.azhukov.agent.persistence.entity.CronJobEntity();
        oneShot.setRepeatCount(1);
        assertThat((Boolean) m.invoke(null, oneShot)).isFalse();
        var bounded = new com.azhukov.agent.persistence.entity.CronJobEntity();
        bounded.setRepeatCount(5);
        assertThat((Boolean) m.invoke(null, bounded)).isTrue();
        var unbounded = new com.azhukov.agent.persistence.entity.CronJobEntity();
        unbounded.setRepeatCount(null); // null = recurring (only KIND_ONCE + null is a one-shot)
        assertThat((Boolean) m.invoke(null, unbounded)).isTrue();
    }
}
