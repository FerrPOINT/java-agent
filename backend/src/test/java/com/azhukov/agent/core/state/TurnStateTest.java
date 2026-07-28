package com.azhukov.agent.core.state;

import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnStateTest {

    @Test
    void recordsExecutionsAndFailures() {
        TurnState state = new TurnState("s1", 1);
        state.recordModelCall();
        state.recordExecution(new ToolCall("tc-read_file", "read_file", "{}"), ToolResult.ok("data"), 10L);
        state.recordExecution(new ToolCall("tc-read_file", "read_file", "{}"), ToolResult.fail("err"), 20L);

        assertThat(state.modelCalls()).isEqualTo(1);
        assertThat(state.totalExecutions()).isEqualTo(2);
        assertThat(state.failureCountFor("read_file")).isEqualTo(1);
        assertThat(state.repeatCountFor(new ToolCall("tc-read_file", "read_file", "{}"))).isEqualTo(2);
    }

    @Test
    void repeatedFailureDetection() {
        TurnState state = new TurnState("s1", 1);
        ToolCall call = new ToolCall("tc-read_file", "read_file", "{}");
        state.recordExecution(call, ToolResult.ok("a"), 1L);
        state.recordExecution(call, ToolResult.ok("b"), 1L);
        assertThat(state.isRepeatedFailure(call)).isTrue();
    }

    @Test
    void haltState() {
        TurnState state = new TurnState("s1", 1);
        state.halt("too many");
        assertThat(state.isHalted()).isTrue();
        assertThat(state.haltReason()).isEqualTo("too many");
        assertThat(state.sessionId()).isEqualTo("s1");
        assertThat(state.turnIndex()).isEqualTo(1);
    }

    @Test
    void executionsAreUnmodifiable() {
        TurnState state = new TurnState("s1", 1);
        state.recordExecution(new ToolCall("tc-x", "x", "{}"), ToolResult.ok(""), 1L);
        assertThatThrownBy(() -> state.executions().add(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
