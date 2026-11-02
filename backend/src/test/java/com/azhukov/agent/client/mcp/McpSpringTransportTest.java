package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpSpringTransportTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({McpServerAutoConfiguration.class, com.azhukov.agent.config.SecurityConfig.class})
    static class Application {
        @Bean AgentProperties properties() {
            var p = new AgentProperties();
            p.getMcp().getServer().setEnabled(true);
            p.getMcp().getServer().setTransport("sse");
            p.getMcp().getServer().setSseEndpoint("/mcp/sse");
            p.getMcp().getServer().setMessageEndpoint("/mcp/message");
            return p;
        }
        @Bean com.azhukov.agent.api.filter.ApiKeyAuthFilter apiKeyAuthFilter(AgentProperties p) {
            return new com.azhukov.agent.api.filter.ApiKeyAuthFilter(p);
        }
        @Bean com.azhukov.agent.api.filter.SecurityHeadersFilter headersFilter() {
            return new com.azhukov.agent.api.filter.SecurityHeadersFilter();
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ToolRegistry registry() {
            var r = mock(ToolRegistry.class);
            when(r.getDefinitions()).thenReturn(List.of(new ToolDefinition("echo", "Echo", Map.of("type", "object", "properties", Map.of()))));
            when(r.execute(anyString(), anyString(), anyString(), any(), any())).thenReturn(ToolResult.ok("spring transport works"));
            return r;
        }
        @Bean McpServerService mcpServer(AgentProperties p, ObjectMapper m, ToolRegistry r) {
            return new McpServerService(p, m, r);
        }
    }

    @Test
    void springBootRegistersServletAndAcceptsMcpCalls() {
        var app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
            "agent.mcp.server.enabled", "true", "agent.mcp.server.transport", "sse",
            "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"));
        try (var context = (ServletWebServerApplicationContext) app.run()) {
            assertThat(context.getBean(McpServerService.class).isRunning()).isTrue();
            int port = context.getWebServer().getPort();
            var transport = HttpClientSseClientTransport.builder("http://127.0.0.1:" + port).sseEndpoint("/mcp/sse").build();
            try (var client = McpClient.sync(transport).build()) {
                client.initialize();
                assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("echo");
                var result = client.callTool(new McpSchema.CallToolRequest("echo", Map.of()));
                assertThat(result.isError()).isNotEqualTo(true);
                assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).isEqualTo("spring transport works");
            }
        }
    }
}
