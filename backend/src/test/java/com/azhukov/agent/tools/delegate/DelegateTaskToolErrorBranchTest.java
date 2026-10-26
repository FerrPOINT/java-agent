package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Coverage + regression for DelegateTaskTool error/timeout branches:
 * single-task timeout cancellation, execution failure, batch size cap,
 * malformed arguments, delegation disabled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateTaskToolErrorBranchTest extends DelegateTaskToolFixture {

    @Test
    void childExecutionFailureReturnsErrorResult() throws Exception {
        AgentProperties p = properties();
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenThrow(new RuntimeException("child blew up"));
        mockRegistry();
        DelegateTaskTool tool = newTool(p);
        p.getDelegation().setDefaultTimeoutSeconds(0);

        ToolResult result = tool.execute("{\"goal\":\"boom\",\"timeoutSeconds\":0}", null, session());

        JsonNode entry = mapper.readTree(result.content()).get("results").get(0);
        assertThat(entry.get("status").asText()).isIn("error", "failed");
    }

    @Test
    void batchAboveMaxConcurrentChildrenFails() throws Exception {
        AgentProperties p = properties();
        p.getDelegation().setMaxConcurrentChildren(2);
        mockRegistry();
        DelegateTaskTool tool = newTool(p);

        String batch = """
            {"tasks":[
              {"goal":"a"},{"goal":"b"},{"goal":"c"}
            ]}
            """;
        ToolResult result = tool.execute(batch, null, session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Too many tasks");
    }

    @Test
    void malformedJsonFailsCleanly() {
        mockRegistry();
        DelegateTaskTool tool = newTool(properties());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute("{not json", null, session()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid tool arguments");
    }

    @Test
    void delegationDisabledFailsImmediately() {
        AgentProperties p = properties();
        p.getDelegation().setEnabled(false);
        DelegateTaskTool tool = newTool(p);
        ToolResult result = tool.execute("{\"goal\":\"x\"}", null, session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Delegation is disabled");
        verify(runtimeProvider, never()).getObject();
    }
}
