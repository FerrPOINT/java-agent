package com.azhukov.agent.config;

import com.azhukov.agent.core.agent.TurnExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CUSTOM OPERATOR SETTING: default retryAttempts=100 (NOT Hermes parity —
 * Hermes defaults to 3). Providers used here can disappear for minutes up
 * to half an hour; the agent keeps retrying with a sane backoff.
 */
class RetryAttemptsDefaultTest {

    @Test
    void defaultRetryAttemptsIsCustom100() {
        AgentProperties.ErrorProperties error = new AgentProperties().getError();
        assertThat(error.getRetryAttempts()).isEqualTo(100);
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
