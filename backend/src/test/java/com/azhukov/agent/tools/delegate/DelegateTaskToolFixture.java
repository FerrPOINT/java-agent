package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

abstract class DelegateTaskToolFixture {

    final ObjectMapper mapper = new ObjectMapper();

    @Mock AgentRuntime agentRuntime;
    @Mock ObjectProvider<AgentRuntime> runtimeProvider;
    @Mock ToolRegistry toolRegistry;
    @Mock ObjectProvider<ToolRegistry> registryProvider;

    AgentProperties properties() {
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

    DelegateTaskTool newTool(AgentProperties p) {
        return new DelegateTaskTool(p, runtimeProvider, registryProvider, 5);
    }

    Session session() {
        return new Session(UUID.randomUUID(), "user", "test", "openai", "gpt-4", null,
            Map.of("delegation_depth", "0"), null);
    }

    void mockRuntimeHangs() {
        lenient().when(runtimeProvider.getObject()).thenReturn(agentRuntime);
        lenient().when(agentRuntime.runTurn(any(Session.class), anyString(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(inv -> {
                Thread.sleep(41_000);
                return new TurnResult(List.of(Message.assistant("never", 0)), true, null);
            });
    }

    void mockRegistry() {
        lenient().when(registryProvider.getIfAvailable()).thenReturn(toolRegistry);
        lenient().when(toolRegistry.getToolsets()).thenReturn(Set.of("web", "file", "terminal"));
    }

}
