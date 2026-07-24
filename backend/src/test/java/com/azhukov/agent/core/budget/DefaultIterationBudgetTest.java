package com.azhukov.agent.core.budget;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultIterationBudgetTest {

    private final AgentPropertiesStub props = new AgentPropertiesStub();
    private final DefaultIterationBudget budget = new DefaultIterationBudget(props);

    @Test
    void tracksModelCallsAndToolExecutions() {
        var snap = budget.startTurn(UUID.randomUUID());
        assertFalse(snap.exhausted());

        snap = budget.recordModelCall(snap, 100, 50);
        snap = budget.recordToolExecution(snap, "read_file", 100);

        var status = budget.status(snap);
        assertTrue(status.allowed());
        assertThat(status.remainingModelCalls()).isPositive();
        assertThat(status.remainingToolExecutions()).isPositive();
    }

    @Test
    void exhaustsAfterMaxModelCalls() {
        var snap = budget.startTurn(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            snap = budget.recordModelCall(snap, 1, 1);
        }
        assertTrue(snap.exhausted());
        assertTrue(budget.isExhausted(snap));
        assertFalse(budget.status(snap).allowed());
    }

    @Test
    void exhaustsAfterMaxToolExecutions() {
        var snap = budget.startTurn(UUID.randomUUID());
        for (int i = 0; i < 20; i++) {
            snap = budget.recordToolExecution(snap, "x", 1);
        }
        assertTrue(snap.exhausted());
        assertTrue(budget.isExhausted(snap));
    }

    @Test
    void disabledBudgetNeverExhausts() {
        props.setBudgetEnabled(false);
        var snap = budget.startTurn(UUID.randomUUID());
        for (int i = 0; i < 1000; i++) {
            snap = budget.recordModelCall(snap, 1000, 1000);
        }
        assertFalse(snap.exhausted());
    }
}
