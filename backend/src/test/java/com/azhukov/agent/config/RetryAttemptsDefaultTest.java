package com.azhukov.agent.config;

import com.azhukov.agent.core.agent.TurnExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-tier retry policy (operator decision 2026-08-28):
 * plain model errors → 3 attempts (Hermes parity api_max_retries=3);
 * availability errors (RATE_LIMIT/OVERLOADED/cooldowns) → 20 attempts
 * because providers used here can disappear for minutes up to half an hour.
 */
class RetryAttemptsDefaultTest {

    @Test
    void twoTierRetryDefaults() {
        AgentProperties.ErrorProperties error = new AgentProperties().getError();
        // Tier 1: plain errors fail fast
        assertThat(error.getRetryAttempts()).isEqualTo(3);
        // Tier 2: availability errors get a dedicated budget
        assertThat(error.getAvailabilityRetryAttempts()).isEqualTo(20);
        // Sane backoff: exponential base 1s, cap 120s (fixed+jitter after)
        assertThat(error.getRetryDelayMs()).isEqualTo(1000);
        assertThat(error.getBackoffMultiplier()).isEqualTo(2);
        assertThat(error.getRetryCapMs()).isEqualTo(120_000);
    }

    @Test
    void providerCooldownHintHonoredUpTo30Minutes() throws Exception {
        // LiteLLM body: "No deployments available ... Try again in 600 seconds"
        Exception e = new RuntimeException(
            "429: {\"error\": {\"message\": \"No deployments available for selected model, "
            + "Try again in 600 seconds. Passed model=app-test\"}}");
        long ms = TurnExecutorUtilsPublic.extract(e);
        assertThat(ms).isEqualTo(600_000L);
    }
}

/** Test-only bridge to the package-private util. */
class TurnExecutorUtilsPublic {
    static long extract(Exception e) throws Exception {
        Class<?> u = Class.forName("com.azhukov.agent.core.agent.TurnExecutorUtils");
        java.lang.reflect.Method m = u.getMethod("extractRetryAfterMs", Exception.class);
        m.setAccessible(true);
        return (long) m.invoke(null, e);
    }
}
