package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.tools.ToolHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class McpLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(McpLifecycleManager.class);

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private final Map<String, McpServerState> clients = new ConcurrentHashMap<>();

    public McpLifecycleManager(AgentProperties properties, ObjectMapper objectMapper, ApplicationContext applicationContext) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    private ToolRegistry toolRegistry() {
        return applicationContext.getBean(ToolRegistry.class);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        connectConfiguredServers();
    }

    public void connectConfiguredServers() {
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP is disabled.");
            return;
        }
        for (AgentProperties.McpProperties.ServerProperties server : properties.getMcp().getServers()) {
            connect(server);
        }
    }

    public void connect(AgentProperties.McpProperties.ServerProperties server) {
        if (clients.containsKey(server.getName())) {
            return;
        }
        try {
            McpSyncClient client;
            if ("stdio".equalsIgnoreCase(server.getTransport())) {
                ServerParameters params = ServerParameters.builder(server.getCommand())
                    .args(server.getArgs())
                    .build();
                StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
                client = McpClient.sync(transport).build();
            } else if ("sse".equalsIgnoreCase(server.getTransport()) || !server.getBaseUrl().isBlank()) {
                var transport = HttpClientSseClientTransport.builder(server.getBaseUrl()).build();
                client = McpClient.sync(transport).build();
            } else {
                log.warn("MCP server {} has no transport configured", server.getName());
                return;
            }
            client.initialize();
            var tools = client.listTools().tools();
            clients.put(server.getName(), new McpServerState(server, client, tools));
            registerTools(server.getName(), tools);
            log.info("Connected to MCP server {} ({}) with {} tools", server.getName(), server.getTransport(), tools.size());
        } catch (Exception e) {
            log.warn("Failed to connect to MCP server {}: {}", server.getName(), e.getMessage());
        }
    }

    private void registerTools(String serverName, List<McpSchema.Tool> tools) {
        for (McpSchema.Tool tool : tools) {
            String fullName = serverName + "__" + tool.name();
            ToolDefinition definition = convertToolDefinition(fullName, tool);
            toolRegistry().registerDynamic(fullName, definition, new McpToolHandler(serverName, tool.name()));
        }
    }

    public List<McpServerInfo> listServers() {
        return clients.values().stream()
            .map(s -> new McpServerInfo(
                s.properties().getName(),
                s.properties().getBaseUrl(),
                s.properties().getTransport(),
                s.tools().size(),
                s.tools().stream().map(McpSchema.Tool::name).toList()
            ))
            .toList();
    }

    public List<DiscoveredTool> listDiscoveredTools() {
        List<DiscoveredTool> result = new ArrayList<>();
        for (var entry : clients.entrySet()) {
            String serverName = entry.getKey();
            for (McpSchema.Tool tool : entry.getValue().tools()) {
                result.add(new DiscoveredTool(serverName, tool.name(), convertToolDefinition(serverName + "__" + tool.name(), tool)));
            }
        }
        return result;
    }

    public String readResource(String serverName, String uri) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            var result = state.client().readResource(new McpSchema.ReadResourceRequest(uri));
            return result.contents().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("MCP read resource failed: " + e.getMessage(), e);
        }
    }

    public McpSchema.CallToolResult executeTool(String serverName, String toolName, String argumentsJson) {
        var state = clients.get(serverName);
        if (state == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            return state.client().callTool(new McpSchema.CallToolRequest(toolName, args));
        } catch (Exception e) {
            throw new RuntimeException("MCP tool call failed: " + e.getMessage(), e);
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void closeAll() {
        for (var state : clients.values()) {
            try {
                state.client().close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }

    static ToolDefinition convertToolDefinition(String fullName, McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema() != null ? tool.inputSchema() : Map.of();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (schema.containsKey("properties") && schema.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                Object raw = entry.getValue();
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    properties.put(String.valueOf(entry.getKey()), copy);
                } else {
                    properties.put(String.valueOf(entry.getKey()), raw);
                }
            }
        }
        if (schema.containsKey("required") && schema.get("required") instanceof List<?> req) {
            for (Object r : req) {
                required.add(String.valueOf(r));
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);
        return new ToolDefinition(fullName, tool.description(), parameters);
    }

    private record McpServerState(AgentProperties.McpProperties.ServerProperties properties,
                                  McpSyncClient client,
                                  List<McpSchema.Tool> tools) {}

    public record McpServerInfo(String name, String baseUrl, String transport, int toolCount, List<String> toolNames) {}

    public record DiscoveredTool(String serverName, String toolName, ToolDefinition definition) {}

    public class McpToolHandler implements ToolHandler {
        private final String serverName;
        private final String toolName;

        McpToolHandler(String serverName, String toolName) {
            this.serverName = serverName;
            this.toolName = toolName;
        }

        @Override
        public ToolResult execute(String arguments, Message lastAssistant, Session session) {
            try {
                var result = executeTool(serverName, toolName, arguments);
                String text = result.content().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
                return ToolResult.ok(text);
            } catch (Exception e) {
                return ToolResult.fail("MCP tool failed: " + e.getMessage());
            }
        }
    }
}
