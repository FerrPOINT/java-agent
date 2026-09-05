package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract: every name promised by the hermes-api-server preset has a real
 * registered @AgentTool handler. Regression for delete_file/mcp_tool being
 * implemented but invisible to HTTP API sessions.
 */
class ApiServerToolsetContractTest {

    @AgentTool(name = "delete_file", description = "delete", toolset = "file")
    static class DeleteFileHandler implements ToolHandler {
        @Override public ToolResult execute(String a, Message m, Session s) { return ToolResult.ok("ok"); }
    }

    @AgentTool(name = "mcp_tool", description = "mcp", toolset = "mcp")
    static class McpHandler implements ToolHandler {
        @Override public ToolResult execute(String a, Message m, Session s) { return ToolResult.ok("ok"); }
    }

    @Test
    void apiServerPresetExposesImplementedFileDeletionAndMcpBridge() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("deleteFile", new DeleteFileHandler());
        context.getBeanFactory().registerSingleton("mcp", new McpHandler());
        context.refresh();
        SpringToolRegistry registry = new SpringToolRegistry(context, new AgentProperties(),
            new ObjectMapper(), new ManagedToolGate(new AgentProperties()));
        registry.registerBeans();

        List<ToolDefinition> definitions = registry.getDefinitions(Set.of("hermes-api-server"));

        assertThat(definitions).extracting(ToolDefinition::name)
            .contains("delete_file", "mcp_tool");
    }
}
