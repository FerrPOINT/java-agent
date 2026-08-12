package com.azhukov.agent.api.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.browser.ChromiumAutoStart;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChromiumHealthIndicator implements HealthIndicator {

    private final ChromiumAutoStart chromiumAutoStart;
    private final AgentProperties properties;

    @Override
    public Health health() {
        if (!properties.getChromium().isAutoStart()) {
            return Health.up()
                .withDetail("status", "not_configured")
                .withDetail("reason", "Chromium auto-start is disabled")
                .build();
        }
        if (chromiumAutoStart.isRunning()) {
            return Health.up().withDetail("cdpUrl", chromiumAutoStart.getCdpUrl() != null ? chromiumAutoStart.getCdpUrl() : "running").build();
        }
        return Health.down().withDetail("reason", "Chromium auto-start is enabled but not running").build();
    }
}