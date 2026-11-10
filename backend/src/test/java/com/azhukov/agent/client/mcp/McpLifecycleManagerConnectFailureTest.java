package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpLifecycleManagerConnectFailureTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void closesClientWhenHandshakeFails(boolean failListing) {
        var properties = new AgentProperties();
        var server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("test");
        var client = mock(io.modelcontextprotocol.client.McpSyncClient.class);
        var failure = new IllegalStateException("handshake failed");
        if (failListing) {
            when(client.listTools()).thenThrow(failure);
        } else {
            org.mockito.Mockito.doThrow(failure).when(client).initialize();
        }
        var manager = org.mockito.Mockito.spy(new McpLifecycleManager(properties, new ObjectMapper(),
            mock(ApplicationContext.class), null, null, null, null, null, null));
        org.mockito.Mockito.doReturn(client).when(manager).createClient(server);

        try {
            manager.connect(server);
            org.mockito.Mockito.verify(client).close();
            org.assertj.core.api.Assertions.assertThat(manager.isConnected("test")).isFalse();
        } finally {
            manager.closeAll();
        }
    }

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

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), ctx, null, null, null, null, null, null);
        manager.connect(server);

        // No exception expected; client not connected.
    }
}
