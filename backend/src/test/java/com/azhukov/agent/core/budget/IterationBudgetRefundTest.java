package com.azhukov.agent.core.budget;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity (conversation_loop.py:7277-7280): iterations whose ONLY
 * tool(s) were execute_code are refunded so programmatic calls don't eat
 * the per-turn budget.
 */
class IterationBudgetRefundTest {

    private DefaultIterationBudget budget() {
        AgentProperties props = new AgentProperties();
        return new DefaultIterationBudget(props);
    }

    @Test
    void refundDecrementsToolExecutions() {
        DefaultIterationBudget b = budget();
        var snap = b.startTurn(UUID.randomUUID());
        snap = b.recordToolExecution(snap, "terminal", 100);
        snap = b.recordToolExecution(snap, "execute_code", 50);
        assertThat(snap.toolExecutions()).isEqualTo(2);
        snap = b.refundToolExecution(snap);
        assertThat(snap.toolExecutions()).isEqualTo(1);
    }

    @Test
    void refundClearsExhaustedFlag() {
        // fill the tool budget exactly (40 via yml default is irrelevant here;
        // AgentProperties default = 200), then refund restores capacity
        DefaultIterationBudget b = budget();
        var snap = b.startTurn(UUID.randomUUID());
        for (int i = 0; i < 200; i++) {
            snap = b.recordToolExecution(snap, "execute_code", 1);
        }
        assertThat(b.isExhausted(snap)).isTrue();
        var refunded = b.refundToolExecution(snap);
        assertThat(refunded.exhausted()).isFalse();
        assertThat(b.isExhausted(refunded)).isFalse();
    }

    @Test
    void refundAtZeroIsNoop() {
        DefaultIterationBudget b = budget();
        var snap = b.startTurn(UUID.randomUUID());
        var refunded = b.refundToolExecution(snap);
        assertThat(refunded.toolExecutions()).isZero();
        assertThat(refunded).isEqualTo(snap);
    }

    @Test
    void refundKeepsModelCallsAndTokens() {
        DefaultIterationBudget b = budget();
        var snap = b.startTurn(UUID.randomUUID());
        snap = b.recordModelCall(snap, 100, 20);
        snap = b.recordToolExecution(snap, "execute_code", 5);
        var refunded = b.refundToolExecution(snap);
        assertThat(refunded.modelCalls()).isEqualTo(1);
        assertThat(refunded.totalInputTokens()).isEqualTo(100);
        assertThat(refunded.totalOutputTokens()).isEqualTo(20);
    }
}
