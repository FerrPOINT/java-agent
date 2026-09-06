package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
class DelegateTaskToolErrorBranchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private AgentRuntime agentRuntime;
    @Mock private ObjectProvider<AgentRuntime> runtimeProvider;
    @Mock private ToolRegistry toolRegistry;
    @Mock private ObjectProvider<ToolRegistry> registryProvider;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getDelegation().setEnabled(true);
        p.getDelegation().setMaxSpawnDepth(3);
        p.getDelegation().setMaxConcurrentChildren(5);
        p.getDelegation().setDefaultTimeoutSeconds(60);
        p.getDelegation().setChildTimeoutSeconds(0);
        p.getDelegation().setMaxIterations(10);
        p.getDelegation().setOrchestratorEnabled(true);
        p.getSkills().setDefaultToolsets(List.of("web", "file", "terminal"));
        return p;
    }

    private DelegateTaskTool newTool(AgentProperties p) {
        return new DelegateTaskTool(p, runtimeProvider, registryProvider, 5);
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "0"), null);
    }

    private void mockRuntimeHangs() {
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(inv -> {
                Thread.sleep(41_000);
                return new TurnResult(List.of(Message.assistant("never", 0)), true, null);
            });
    }

    private void mockRegistry() {
        lenient().when(registryProvider.getIfAvailable()).thenReturn(toolRegistry);
        lenient().when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "file", "terminal"));
    }

    @Test
    void singleTaskTimeoutReturnsTimeoutResult() throws Exception {
        AgentProperties p = properties();
        mockRuntimeHangs();
        mockRegistry();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"goal\":\"slow task\",\"timeoutSeconds\":1}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode entry = mapper.readTree(result.content()).get("results").get(0);
        assertThat(entry.get("status").asText()).isEqualTo("timeout");
    }

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
