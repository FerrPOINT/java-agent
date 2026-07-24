package com.azhukov.agent.api.health;

import com.azhukov.agent.tools.browser.ChromiumAutoStart;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ChromiumHealthIndicator implements HealthIndicator {

    private final ChromiumAutoStart chromiumAutoStart;

    public ChromiumHealthIndicator(ChromiumAutoStart chromiumAutoStart) {
        this.chromiumAutoStart = chromiumAutoStart;
    }

    @Override
    public Health health() {
        if (chromiumAutoStart.isRunning()) {
            return Health.up().withDetail("cdpUrl", chromiumAutoStart.getCdpUrl() != null ? chromiumAutoStart.getCdpUrl() : "running").build();
        }
        return Health.down().withDetail("reason", "Chromium auto-start is not running").build();
    }
}
