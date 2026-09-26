package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.budget.IterationBudget.TurnSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for TurnExecutor static helpers and FallbackContext accessors
 * (no full TurnExecutor construction needed — helpers are static/context-only).
 */
class TurnExecutorHelpersTest {

    @Test
    void refusalPatternsAreDetected() {
        // TurnExecutorUtils patterns: "I cannot", "I can't", "I'm unable to" …
        assertThat(TurnExecutor.detectRefusalPattern("I cannot help with that request"))
            .isNotNull();
        assertThat(TurnExecutor.detectRefusalPattern("I'm unable to comply"))
            .isNotNull();
        assertThat(TurnExecutor.detectRefusalPattern("I won't be able to do that"))
            .isNotNull();
        assertThat(TurnExecutor.detectRefusalPattern("Request refused due to policy"))
            .isNull();
        assertThat(TurnExecutor.detectRefusalPattern("Sure, here is your answer"))
            .isNull();
        assertThat(TurnExecutor.detectRefusalPattern(null)).isNull();
    }

    @Test
    void budgetExhaustionFallbackExposesTheActualLimitAndCounters() {
        AgentProperties properties = new AgentProperties();
        TurnExecutor executor = new TurnExecutor(null, properties, null, null, null, null,
            null, null, null, null, null, null);
        TurnSnapshot budget = new TurnSnapshot(UUID.randomUUID(), Instant.now(), 1,
            7, 12, 120_000, 80_000, 45_000, true, "max tokens reached");

        assertThat(executor.formatBudgetExhaustionMessage(budget, "max tokens reached"))
            .isEqualTo("Iteration budget exhausted: reason=max tokens reached; "
                + "model_calls=7/100; tool_executions=12/100; "
                + "estimated_tokens=200000/200000; tool_duration_ms=45000/600000.");
    }

    @Test
    void fallbackContextTracksActiveClient() {
        TurnExecutor.FallbackContext ctx = new TurnExecutor.FallbackContext(null);
        assertThat(ctx.getActiveModelClient()).isNull();
        assertThat(ctx.getPrimaryModelClient()).isNull();
        assertThat(ctx.getFallbackManager()).isNull();

        FallbackManager fm = new FallbackManager(List.of(), null, null, null, null);
        ctx.setFallbackManager(fm);
        ctx.setActiveModelClient(null);
        assertThat(ctx.getFallbackManager()).isSameAs(fm);
        assertThat(ctx.getActiveModelClient()).isNull();
    }
}
