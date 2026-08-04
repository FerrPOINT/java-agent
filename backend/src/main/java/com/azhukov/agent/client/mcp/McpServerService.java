package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server mode: exposes the agent's {@link ToolRegistry} tools to external MCP clients
 * (Claude Desktop, IDEs, other agents) via the MCP protocol.
 *
 * <p>Supports two transports:
 * <ul>
 *   <li><b>stdio</b> — communicates over stdin/stdout, suitable for CLI integration
 *       (e.g. when launched as a subprocess by an MCP client).</li>
 *   <li><b>sse</b> — HTTP SSE transport, registers a servlet at the configured endpoints
 *       so remote clients can connect over the network.</li>
 * </ul>
 *
 * <p>Configuration is via {@code agent.mcp.server.*} in {@code application.yml}.
 * The server is only started when {@code agent.mcp.server.enabled=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpServerService {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    private McpSyncServer mcpServer;
    private McpServerTransportProvider transportProvider;
    private ServletRegistrationBean<?> servletRegistration;

    @PostConstruct
    void init() {
        // Server starts on ContextRefreshedEvent to ensure all tools are registered first.
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        start();
    }

    /**
     * Starts the MCP server if {@code agent.mcp.server.enabled} is true.
     * Registers all tools from the {@link ToolRegistry} as MCP tool specifications.
     */
    public void start() {
        AgentProperties.McpProperties.Server config = properties.getMcp().getServer();
        if (!config.isEnabled()) {
            log.debug("MCP server mode is disabled");
            return;
        }

        try {
            List<McpServerFeatures.SyncToolSpecification> toolSpecs = buildToolSpecifications();
            log.info("Starting MCP server ({} mode) with {} tools", config.getTransport(), toolSpecs.size());

            McpSchema.Implementation serverInfo = new McpSchema.Implementation(
                config.getName(), config.getVersion());

            McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build();

            transportProvider = createTransportProvider(config);

            mcpServer = McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(toolSpecs)
                .build();

            log.info("MCP server '{}' v{} started with {} tools via {} transport",
                config.getName(), config.getVersion(), toolSpecs.size(), config.getTransport());
        } catch (Exception e) {
            log.error("Failed to start MCP server: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds MCP tool specifications from all registered {@link ToolRegistry} tools.
     */
    private List<McpServerFeatures.SyncToolSpecification> buildToolSpecifications() {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (ToolDefinition def : toolRegistry.getDefinitions()) {
            McpSchema.Tool tool = McpSchema.Tool.builder(def.name(), def.parameters())
                .description(def.description())
                .build();

            McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handleToolCall(def.name(), request))
                .build();

            specs.add(spec);
        }
        return specs;
    }

    /**
     * Handles an MCP tool call by delegating to the agent's {@link ToolRegistry}.
     */
    private McpSchema.CallToolResult handleToolCall(String toolName, McpSchema.CallToolRequest request) {
        try {
            String argumentsJson = objectMapper.writeValueAsString(request.arguments());
            Session session = Session.create("mcp-client", "mcp", "mcp");
            ToolResult result = toolRegistry.execute(toolName, "mcp-" + System.nanoTime(),
                argumentsJson, Message.user(""), session);

            McpSchema.CallToolResult.Builder builder = McpSchema.CallToolResult.builder()
                .addTextContent(result.success() ? result.content() : result.error());

            if (!result.success()) {
                builder.isError(true);
            }

            return builder.build();
        } catch (Exception e) {
            log.error("MCP tool call failed for '{}': {}", toolName, e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    /**
     * Creates the transport provider based on the configured transport type.
     */
    private McpServerTransportProvider createTransportProvider(AgentProperties.McpProperties.Server config) {
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

        if ("sse".equalsIgnoreCase(config.getTransport())) {
            HttpServletSseServerTransportProvider sseProvider = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .sseEndpoint(config.getSseEndpoint())
                .messageEndpoint(config.getMessageEndpoint())
                .build();

            // Register the SSE servlet with Spring Boot's embedded servlet container
            servletRegistration = new ServletRegistrationBean<>(sseProvider,
                config.getSseEndpoint(), config.getMessageEndpoint());
            servletRegistration.setLoadOnStartup(1);

            log.info("MCP SSE transport: sse={}, message={}", config.getSseEndpoint(), config.getMessageEndpoint());
            return sseProvider;
        }

        // Default: stdio
        log.info("MCP stdio transport initialized");
        return new StdioServerTransportProvider(jsonMapper);
    }

    /**
     * Returns the servlet registration bean for SSE transport, or null if stdio.
     * This is used by a configuration class to register the servlet.
     */
    public ServletRegistrationBean<?> getServletRegistration() {
        return servletRegistration;
    }

    /**
     * Returns whether the MCP server is running.
     */
    public boolean isRunning() {
        return mcpServer != null;
    }

    /**
     * Returns the list of tool names exposed by the MCP server.
     */
    public List<String> getExposedToolNames() {
        if (mcpServer == null) {
            return List.of();
        }
        return mcpServer.listTools().stream()
            .map(McpSchema.Tool::name)
            .toList();
    }

    @PreDestroy
    public void stop() {
        if (mcpServer != null) {
            try {
                mcpServer.closeGracefully();
                log.info("MCP server stopped");
            } catch (Exception e) {
                log.warn("Error stopping MCP server: {}", e.getMessage());
            }
        }
        if (transportProvider != null) {
            try {
                transportProvider.closeGracefully().block();
            } catch (Exception e) {
                log.warn("Error closing MCP transport: {}", e.getMessage());
            }
        }
        mcpServer = null;
        transportProvider = null;
    }
}