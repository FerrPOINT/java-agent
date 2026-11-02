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
        @Bean com.azhukov.agent.service.UserAccessService users() {
            var users = mock(com.azhukov.agent.service.UserAccessService.class);
            when(users.authenticate("alice-key")).thenReturn(new com.azhukov.agent.service.UserAccessService.AuthenticatedUser("alice", "user"));
            when(users.authenticate("bob-key")).thenReturn(new com.azhukov.agent.service.UserAccessService.AuthenticatedUser("bob", "user"));
            return users;
        }
        @Bean com.azhukov.agent.api.filter.ApiKeyAuthFilter apiKeyAuthFilter(AgentProperties p, com.azhukov.agent.service.UserAccessService users) {
            return new com.azhukov.agent.api.filter.ApiKeyAuthFilter(p, users);
        }
        @Bean com.azhukov.agent.api.filter.SecurityHeadersFilter headersFilter() {
            return new com.azhukov.agent.api.filter.SecurityHeadersFilter();
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ToolRegistry registry() {
            var r = mock(ToolRegistry.class);
            when(r.getDefinitions()).thenReturn(List.of(new ToolDefinition("echo", "Echo", Map.of("type", "object", "properties", Map.of()))));
            when(r.execute(anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
                var session = (com.azhukov.agent.core.model.Session) inv.getArgument(4);
                assertThat(com.azhukov.agent.core.security.UserContext.getUserId()).isEqualTo(session.userId());
                return ToolResult.ok("spring transport works:" + session.userId());
            });
            return r;
        }
        @Bean McpServerService mcpServer(AgentProperties p, ObjectMapper m, ToolRegistry r) {
            return new McpServerService(p, m, r);
        }
    }

    @Test
    void apiKeyRequiredAndAuthenticatedClientCanCallMcp() throws Exception {
        var app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
            "agent.mcp.server.enabled", "true", "agent.mcp.server.transport", "sse",
            "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"));
        try (var context = (ServletWebServerApplicationContext) app.run("--agent.security.api-key=test-only-key")) {
            assertThat(context.getBean(AgentProperties.class).getSecurity().getApiKey()).isEqualTo("test-only-key");
            String url = "http://127.0.0.1:" + context.getWebServer().getPort();
            var http = java.net.http.HttpClient.newHttpClient();
            var denied = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/mcp/sse"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored = denied.body()) { assertThat(denied.statusCode()).isEqualTo(401); }
            var wrong = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/mcp/sse"))
                .timeout(java.time.Duration.ofSeconds(5)).header("X-API-Key", "wrong-test-key").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (var ignored = wrong.body()) { assertThat(wrong.statusCode()).isEqualTo(401); }
            var transport = HttpClientSseClientTransport.builder(url).sseEndpoint("/mcp/sse")
                .httpRequestCustomizer((builder, method, uri, body, transportContext) -> builder.header("X-API-Key", "test-only-key"))
                .build();
            try (var client = McpClient.sync(transport).initializationTimeout(java.time.Duration.ofSeconds(8)).build()) {
                client.initialize();
                var result = client.callTool(new McpSchema.CallToolRequest("echo", Map.of()));
                assertThat(result.isError()).isNotEqualTo(true);
                assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).startsWith("spring transport works:");
            }
            var registry = context.getBean(ToolRegistry.class);
            verify(registry).execute(eq("echo"), anyString(), anyString(), any(),
                argThat(session -> com.azhukov.agent.config.AgentProperties.DEFAULT_USER_ID.equals(session.userId())));
        }
    }

    @Test
    void individualKeysRetainTheirOwnIdentityAcrossRepeatedMcpCalls() {
        var app = new SpringApplication(Application.class);
        app.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
            "agent.mcp.server.enabled", "true", "agent.mcp.server.transport", "sse",
            "spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"));
        try (var context = (ServletWebServerApplicationContext) app.run("--agent.security.api-key=test-only-key")) {
            String url = "http://127.0.0.1:" + context.getWebServer().getPort();
            var aTransport = HttpClientSseClientTransport.builder(url).sseEndpoint("/mcp/sse")
                .httpRequestCustomizer((builder, method, uri, body, transportContext) -> builder.header("X-API-Key", "alice-key")).build();
            var bTransport = HttpClientSseClientTransport.builder(url).sseEndpoint("/mcp/sse")
                .httpRequestCustomizer((builder, method, uri, body, transportContext) -> builder.header("X-API-Key", "bob-key")).build();
            try (var a = McpClient.sync(aTransport).build(); var b = McpClient.sync(bTransport).build()) {
                a.initialize(); b.initialize();
                var request = new McpSchema.CallToolRequest("echo", Map.of());
                assertThat(((McpSchema.TextContent) a.callTool(request).content().getFirst()).text()).endsWith(":alice");
                assertThat(((McpSchema.TextContent) b.callTool(request).content().getFirst()).text()).endsWith(":bob");
                assertThat(((McpSchema.TextContent) a.callTool(request).content().getFirst()).text()).endsWith(":alice");
            }
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
                assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).startsWith("spring transport works:");
            }
        }
    }
}
