package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpLifecycleManagerConnectFailureTest {

    @Test
    void connectStdioFailureIsLoggedAndSwallowed() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);
        AgentProperties.McpProperties.ServerProperties server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("bad");
        server.setTransport("stdio");
        server.setCommand("/nonexistent-binary-xyz");
        properties.getMcp().getServers().add(server);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(ToolRegistry.class)).thenReturn(mock(ToolRegistry.class));

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx);
        manager.connect(server);

        // No exception expected; client not connected.
    }
}
