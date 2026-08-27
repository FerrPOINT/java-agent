package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P-08: per-turn cap on escaped outer-loop exceptions (Hermes #92450). */
class OuterErrorBudgetTest {

    @Test
    void capIsMinOfEightAndIterationFloor() {
        assertThat(new OuterErrorBudget(100).cap()).isEqualTo(8);
        assertThat(new OuterErrorBudget(3).cap()).isEqualTo(3);
        assertThat(new OuterErrorBudget(1).cap()).isEqualTo(1);
        assertThat(new OuterErrorBudget(0).cap()).isEqualTo(1);
        assertThat(new OuterErrorBudget(-5).cap()).isEqualTo(1);
    }

    @Test
    void exhaustedAtCap() {
        OuterErrorBudget b = new OuterErrorBudget(100);
        for (int i = 1; i <= 7; i++) {
            assertThat(b.recordAndCheckExhausted()).as("attempt " + i).isFalse();
        }
        assertThat(b.recordAndCheckExhausted()).isTrue();
        assertThat(b.count()).isEqualTo(8);
    }

    @Test
    void messageCarriesCountAndLastError() {
        OuterErrorBudget b = new OuterErrorBudget(8);
        for (int i = 0; i < 8; i++) {
            b.recordAndCheckExhausted();
        }
        String msg = b.exhaustedMessage("boom");
        assertThat(msg).contains("repeated errors").contains("8/8").contains("boom");
        assertThat(b.exhaustedMessage(null)).contains("unknown");
    }
}
