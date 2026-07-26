package com.azhukov.agent.core.state;

import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnStateTest {

    @Test
    void recordsModelCallAndExecution() {
        TurnState state = new TurnState("s1", 1);
        assertThat(state.modelCalls()).isEqualTo(0);
        assertThat(state.totalExecutions()).isEqualTo(0);

        state.recordModelCall();
        assertThat(state.modelCalls()).isEqualTo(1);

        state.recordExecution(new ToolCall("c1", "tool", "{}"), ToolResult.ok("res"), 10L);
        assertThat(state.totalExecutions()).isEqualTo(1);
        assertThat(state.failureCountFor("tool")).isEqualTo(0);
    }

    @Test
    void tracksFailuresAndRepeats() {
        TurnState state = new TurnState("s1", 1);
        ToolCall call = new ToolCall("c1", "tool", "{}");
        state.recordExecution(call, ToolResult.fail("err"), 5L);
        state.recordExecution(call, ToolResult.fail("err"), 5L);
        assertThat(state.failureCountFor("tool")).isEqualTo(2);
        assertThat(state.repeatCountFor(call)).isEqualTo(2);
    }

    @Test
    void haltedStateStored() {
        TurnState state = new TurnState("s1", 1);
        state.halt("too many failures");
        assertThat(state.isHalted()).isTrue();
        assertThat(state.haltReason()).isEqualTo("too many failures");
    }

    @Test
    void executionsListIsUnmodifiable() {
        TurnState state = new TurnState("s1", 1);
        state.recordExecution(new ToolCall("c1", "tool", "{}"), ToolResult.ok("x"), 1L);
        assertThatThrownBy(() -> state.executions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
