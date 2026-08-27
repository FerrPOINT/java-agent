package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-02: the shared pre-execution tool batch pipeline. The SSE runtime
// and the sync runtime must both route model tool batches through it.
 */
class ToolBatchPipelineTest {

    private final ToolBatchPipeline pipeline = new ToolBatchPipeline();
    private final Set<String> registered = Set.of("weather", "search", "delegate_task", "terminal");

    @Test
    void validCallsPassThroughUnchanged() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "weather", "{\"city\":\"Paris\"}"),
            new ToolCall("c2", "search", "{\"q\":\"x\"}")
        ), registered, 1);
        assertThat(result.truncatedArgs()).isFalse();
        assertThat(result.syntheticResults()).isEmpty();
        assertThat(result.executableCalls()).hasSize(2);
        assertThat(result.executableCalls().get(0).name()).isEqualTo("weather");
        assertThat(result.executableCalls().get(1).name()).isEqualTo("search");
    }

    @Test
    void invalidNameProducesErrorResultAndValidSiblingStillExecutes() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "totally_bogus_name", "{\"city\":\"Paris\"}"), // unrepairable
            new ToolCall("c2", "search", "{\"q\":\"x\"}")
        ), registered, 1);
        assertThat(result.truncatedArgs()).isFalse();
        // h53: valid sibling proceeds
        assertThat(result.executableCalls()).hasSize(1);
        assertThat(result.executableCalls().get(0).name()).isEqualTo("search");
        // invalid call gets a recovery result naming the available tools
        assertThat(result.syntheticResults()).hasSize(1);
        assertThat(result.syntheticResults().get(0).toolCallId()).isEqualTo("c1");
        assertThat(result.syntheticResults().get(0).content()).contains("does not exist");
        assertThat(result.syntheticResults().get(0).content()).contains("weather");
    }

    @Test
    void fuzzyRepairableNameIsFixedAndExecutes() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "weathr", "{\"city\":\"Paris\"}")  // Levenshtein 1 → repaired
        ), registered, 1);
        assertThat(result.syntheticResults()).isEmpty();
        assertThat(result.executableCalls()).hasSize(1);
        assertThat(result.executableCalls().get(0).name()).isEqualTo("weather");
    }

    @Test
    void invalidJsonBlocksWholeBatchWithRecoveryResults() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "weather", "{\"city\": {}"),        // invalid, not truncated-looking
            new ToolCall("c2", "search", "{\"q\":\"x\"}")           // valid
        ), registered, 1);
        assertThat(result.truncatedArgs()).isFalse();
        assertThat(result.executableCalls()).isEmpty();
        assertThat(result.syntheticResults()).hasSize(2);
        assertThat(result.syntheticResults().get(0).content()).contains("Invalid JSON");
        assertThat(result.syntheticResults().get(1).content()).contains("Skipped");
    }

    @Test
    void truncatedArgumentsSignalTerminalAbort() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "terminal", "{\"command\":\"cat /var/log/big")
        ), registered, 1);
        assertThat(result.truncatedArgs()).isTrue();
        assertThat(result.executableCalls()).isEmpty();
        assertThat(result.syntheticResults()).isEmpty();
    }

    @Test
    void duplicateCallsAreDeduplicated() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "search", "{\"q\":\"same\"}"),
            new ToolCall("c2", "search", "{\"q\":\"same\"}")
        ), registered, 1);
        assertThat(result.executableCalls()).hasSize(1);
    }

    @Test
    void delegateTaskCappedToOne() {
        var result = pipeline.prepare(List.of(
            new ToolCall("c1", "delegate_task", "{\"goal\":\"a\"}"),
            new ToolCall("c2", "delegate_task", "{\"goal\":\"b\"}")
        ), registered, 1);
        assertThat(result.executableCalls()).hasSize(1);
        assertThat(result.executableCalls().get(0).pairingId()).isEqualTo("c1");
    }

    @Test
    void duplicateIdsUniquifiedBeforeExecution() {
        var result = pipeline.prepare(List.of(
            new ToolCall("dup", "weather", "{\"city\":\"A\"}"),
            new ToolCall("dup", "search", "{\"q\":\"B\"}")
        ), registered, 1);
        assertThat(result.executableCalls()).hasSize(2);
        assertThat(result.executableCalls().get(0).pairingId())
            .isNotEqualTo(result.executableCalls().get(1).pairingId());
    }

    @Test
    void emptyBatchIsNoop() {
        var result = pipeline.prepare(List.of(), registered, 1);
        assertThat(result.truncatedArgs()).isFalse();
        assertThat(result.executableCalls()).isEmpty();
        assertThat(result.syntheticResults()).isEmpty();
    }

    @Test
    void syntheticResultsUsePairingId() {
        var result = pipeline.prepare(List.of(
            new ToolCall("call_x|fc_y", "nope", "{}")
        ), registered, 1);
        assertThat(result.syntheticResults().get(0).toolCallId()).isEqualTo("call_x");
    }
}
