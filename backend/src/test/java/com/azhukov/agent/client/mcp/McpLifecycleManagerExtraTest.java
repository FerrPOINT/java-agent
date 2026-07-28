package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpLifecycleManagerExtraTest {

    @Test
    void disabledWhenMcpNotEnabled() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(false);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);
        manager.connectConfiguredServers();

        assertThat(manager.listServers()).isEmpty();
        assertThat(manager.listDiscoveredTools()).isEmpty();
    }

    @Test
    void listServersAndDiscoveredToolsEmptyWhenNoConnections() {
        AgentProperties properties = new AgentProperties();
        properties.getMcp().setEnabled(true);

        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);
        assertThat(manager.listServers()).isEmpty();
        assertThat(manager.listDiscoveredTools()).isEmpty();
    }

    @Test
    void readResourceThrowsWhenServerNotConnected() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        assertThatThrownBy(() -> manager.readResource("missing", "resource://foo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP server not connected");
    }

    @Test
    void executeToolThrowsWhenServerNotConnected() {
        AgentProperties properties = new AgentProperties();
        McpLifecycleManager manager = new McpLifecycleManager(properties, new ObjectMapper(), null);

        assertThatThrownBy(() -> manager.executeTool("missing", "tool", "{}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP server not connected");
    }

    @Test
    void convertToolDefinitionWithSchema() {
        McpSchema.Tool tool = McpSchema.Tool.builder("test").title("test").description("desc").inputSchema(Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string")),
            "required", List.of("name")
        )).build();

        var def = McpLifecycleManager.convertToolDefinition("srv__test", tool);

        assertThat(def.name()).isEqualTo("srv__test");
        assertThat(def.description()).isEqualTo("desc");
        assertThat(def.parameters()).containsKeys("type", "properties", "required");
        assertThat(def.parameters().get("required")).asList().contains("name");
    }

    @Test
    void convertToolDefinitionWithEmptySchema() {
        McpSchema.Tool tool = McpSchema.Tool.builder("empty").title("empty").description("no args").inputSchema(Map.of()).build();

        var def = McpLifecycleManager.convertToolDefinition("srv__empty", tool);

        assertThat(def.parameters().get("type")).isEqualTo("object");
        assertThat(def.parameters().get("properties")).isInstanceOf(Map.class);
        assertThat(def.parameters().get("required")).isInstanceOf(List.class);
    }
}
