package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.browser.CdpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrowserHealthIndicator implements HealthIndicator {

    private final CdpClient cdpClient;
    private final AgentProperties properties;

    @Override
    public Health health() {
        String cdpUrl = properties.getBrowser().getCdpUrl();
        try {
            cdpClient.connect(cdpUrl);
            if (cdpClient.isConnected()) {
                return Health.up().withDetail("cdpUrl", cdpUrl).build();
            }
            return Health.down().withDetail("cdpUrl", cdpUrl).withDetail("reason", "not connected").build();
        } catch (Exception e) {
            return Health.down().withDetail("cdpUrl", cdpUrl != null ? cdpUrl : "unknown")
                .withDetail("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName())
                .build();
        }
    }
}
