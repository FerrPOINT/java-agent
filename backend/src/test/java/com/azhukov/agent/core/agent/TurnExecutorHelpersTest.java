package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

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
