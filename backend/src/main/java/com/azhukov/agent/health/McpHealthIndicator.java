package com.azhukov.agent.health;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpHealthIndicator implements HealthIndicator {

    private final McpLifecycleManager mcpLifecycleManager;
    private final AgentProperties properties;

    @Override
    public Health health() {
        if (!properties.getMcp().isEnabled()) {
            return Health.up()
                .withDetail("status", "not_configured")
                .withDetail("reason", "MCP is disabled")
                .build();
        }
        var servers = mcpLifecycleManager.listServers();
        if (servers.isEmpty()) {
            return Health.up()
                .withDetail("status", "not_configured")
                .withDetail("servers", 0)
                .withDetail("reason", "No MCP servers connected")
                .build();
        }
        return Health.up()
            .withDetail("servers", servers.size())
            .withDetail("toolCount", servers.stream().mapToInt(McpLifecycleManager.McpServerInfo::toolCount).sum())
            .build();
    }
}