package com.azhukov.agent.service;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-02 contract: the SSE streaming path routes model tool batches through
 * the SAME ToolBatchPipeline the sync path uses. This pins the wiring at
 * the unit level: AgentStreamingService must not execute raw model calls.
 */
class StreamingToolValidationParityTest {

    @Test
    void streamingServiceHasPipelineDependency() throws Exception {
        var fields = AgentStreamingService.class.getDeclaredFields();
        boolean hasPipeline = false;
        for (var f : fields) {
            if (f.getType() == com.azhukov.agent.core.agent.ToolBatchPipeline.class) {
                hasPipeline = true;
                break;
            }
        }
        assertThat(hasPipeline)
            .as("AgentStreamingService must depend on ToolBatchPipeline (P-02)")
            .isTrue();
    }

    @Test
    void pipelineRejectsUnknownToolBeforeExecution() {
        var pipeline = new com.azhukov.agent.core.agent.ToolBatchPipeline();
        var result = pipeline.prepare(
            List.of(new ToolCall("c1", "not_a_tool", "{}")),
            Set.of("terminal", "read_file"), 1);
        assertThat(result.executableCalls()).isEmpty();
        assertThat(result.syntheticResults()).hasSize(1);
        assertThat(result.syntheticResults().get(0).content()).contains("does not exist");
    }
}
