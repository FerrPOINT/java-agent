package com.azhukov.agent.health;

import com.azhukov.agent.client.mcp.McpLifecycleManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpHealthIndicator implements HealthIndicator {

    private final McpLifecycleManager mcpLifecycleManager;

    @Override
    public Health health() {
        var servers = mcpLifecycleManager.listServers();
        if (servers.isEmpty()) {
            return Health.down().withDetail("servers", 0).build();
        }
        return Health.up()
            .withDetail("servers", servers.size())
            .withDetail("toolCount", servers.stream().mapToInt(McpLifecycleManager.McpServerInfo::toolCount).sum())
            .build();
    }
}
