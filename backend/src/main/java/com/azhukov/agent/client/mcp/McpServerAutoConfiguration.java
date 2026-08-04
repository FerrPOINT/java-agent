package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the MCP SSE servlet (if SSE transport is configured) with the
 * embedded servlet container. The servlet is created by {@link McpServerService}
 * and this config makes it available as a Spring bean so the web server picks it up.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpServerAutoConfiguration {

    private final McpServerService mcpServerService;
    private final AgentProperties properties;

    @PostConstruct
    void registerServlet() {
        // Trigger servlet registration if SSE transport is used.
        // The actual registration happens via the @Bean method below.
    }

    @Bean
    public ServletRegistrationBean<?> mcpSseServletRegistration() {
        AgentProperties.McpProperties.Server config = properties.getMcp().getServer();
        if (!config.isEnabled() || !"sse".equalsIgnoreCase(config.getTransport())) {
            return null; // No servlet registration when MCP server is disabled or using stdio
        }
        return mcpServerService.getServletRegistration();
    }
}