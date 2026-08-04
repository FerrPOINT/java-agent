package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the MCP SSE servlet (if SSE transport is configured) with the
 * embedded servlet container. The servlet is created by {@link McpServerService}
 * and this config makes it available as a Spring bean so the web server picks it up.
 *
 * <p>Only active when {@code agent.mcp.server.enabled=true} AND
 * {@code agent.mcp.server.transport=sse}. When disabled or using stdio,
 * this configuration is skipped entirely to avoid registering a null servlet.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.mcp.server", name = "enabled", havingValue = "true")
public class McpServerAutoConfiguration {

    private final McpServerService mcpServerService;
    private final AgentProperties properties;

    @Bean
    @ConditionalOnProperty(prefix = "agent.mcp.server", name = "transport", havingValue = "sse")
    public ServletRegistrationBean<?> mcpSseServletRegistration() {
        return mcpServerService.getServletRegistration();
    }
}