package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(McpLifecycleManager.class);

    private final AgentProperties properties;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpLifecycleManager(AgentProperties properties) {
        this.properties = properties;
    }

    public void ensureConnected(String serverName, String baseUrl) {
        if (clients.containsKey(serverName)) {
            return;
        }
        try {
            var transport = HttpClientSseClientTransport.builder(baseUrl).build();
            var client = McpClient.sync(transport).build();
            client.initialize();
            clients.put(serverName, client);
            log.info("Connected to MCP server {} at {}", serverName, baseUrl);
        } catch (Exception e) {
            log.warn("Failed to connect to MCP server {} at {}: {}", serverName, baseUrl, e.getMessage());
        }
    }

    public List<McpSchema.Tool> listTools(String serverName) {
        var client = clients.get(serverName);
        if (client == null) {
            return List.of();
        }
        try {
            return client.listTools().tools();
        } catch (Exception e) {
            log.warn("Failed to list tools from {}: {}", serverName, e.getMessage());
            return List.of();
        }
    }

    public McpSchema.CallToolResult executeTool(String serverName, String toolName, String argumentsJson) {
        var client = clients.get(serverName);
        if (client == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            Map<String, Object> args = new ObjectMapper().readValue(argumentsJson, new TypeReference<>() {});
            return client.callTool(new McpSchema.CallToolRequest(toolName, args));
        } catch (Exception e) {
            throw new RuntimeException("MCP tool call failed: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void closeAll() {
        for (var client : clients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }
}
