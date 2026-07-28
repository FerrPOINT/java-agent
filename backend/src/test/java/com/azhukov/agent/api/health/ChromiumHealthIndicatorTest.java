package com.azhukov.agent.api.health;

import com.azhukov.agent.tools.browser.ChromiumAutoStart;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChromiumHealthIndicatorTest {

    @Test
    void reportsUpWhenRunning() {
        ChromiumAutoStart autoStart = mock(ChromiumAutoStart.class);
        when(autoStart.isRunning()).thenReturn(true);
        when(autoStart.getCdpUrl()).thenReturn("http://localhost:9222");

        ChromiumHealthIndicator indicator = new ChromiumHealthIndicator(autoStart);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("cdpUrl")).isEqualTo("http://localhost:9222");
    }

    @Test
    void reportsDownWhenNotRunning() {
        ChromiumAutoStart autoStart = mock(ChromiumAutoStart.class);
        when(autoStart.isRunning()).thenReturn(false);

        ChromiumHealthIndicator indicator = new ChromiumHealthIndicator(autoStart);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
