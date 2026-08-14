package com.azhukov.agent.core.budget;

import com.azhukov.agent.config.AgentProperties;
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
        // Set a low tool execution limit for this test
        props.getBudget().setMaxToolExecutionsPerTurn(5);
        var snap = budget.startTurn(UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            snap = budget.recordToolExecution(snap, "x", 1);
        }
        assertTrue(snap.exhausted());
        assertTrue(budget.isExhausted(snap));
    }

    @Test
    void doesNotExhaustWith200ToolExecutionsAtDefaultLimit() {
        // Verify the new default of 200 tool executions doesn't trigger prematurely
        var snap = budget.startTurn(UUID.randomUUID());
        for (int i = 0; i < 50; i++) {
            snap = budget.recordToolExecution(snap, "x", 1);
        }
        assertFalse(snap.exhausted());
        assertFalse(budget.isExhausted(snap));
    }

    @Test
    void defaultMaxModelCallsIs100MatchingUserPreference() {
        // User wants 100 model calls per turn (100×100 default)
        AgentProperties.BudgetProperties defaultBudget = new AgentProperties.BudgetProperties();
        assertThat(defaultBudget.getMaxModelCallsPerTurn()).isEqualTo(100);
    }

    @Test
    void defaultMaxToolExecutionsIs200Not20() {
        // Bug 1: old default was 20, way too low. New default is 200
        // (effectively unlimited, matching Hermes which doesn't limit tools separately)
        AgentProperties.BudgetProperties defaultBudget = new AgentProperties.BudgetProperties();
        assertThat(defaultBudget.getMaxToolExecutionsPerTurn()).isEqualTo(200);
    }

    @Test
    void defaultMemoryNudgeIntervalIs10() {
        // Hermes default: memory.nudge_interval = 10 (review every 10 user turns)
        AgentProperties.MemoryProperties defaultMemory = new AgentProperties.MemoryProperties();
        assertThat(defaultMemory.getNudgeInterval()).isEqualTo(10);
    }

    @Test
    void defaultSkillCreationNudgeIntervalIs15() {
        // Hermes default: skills.creation_nudge_interval = 15 (review every 15 tool-calling iterations)
        AgentProperties.SkillsProperties defaultSkills = new AgentProperties.SkillsProperties();
        assertThat(defaultSkills.getCreationNudgeInterval()).isEqualTo(15);
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
