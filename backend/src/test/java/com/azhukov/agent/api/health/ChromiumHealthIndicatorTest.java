package com.azhukov.agent.api.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.browser.ChromiumAutoStart;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChromiumHealthIndicatorTest {

    @Test
    void reportsUpWhenRunning() {
        ChromiumAutoStart autoStart = mock(ChromiumAutoStart.class);
        when(autoStart.isRunning()).thenReturn(true);
        when(autoStart.getCdpUrl()).thenReturn("http://localhost:9222");
        AgentProperties props = new AgentProperties();
        props.getChromium().setAutoStart(true);

        ChromiumHealthIndicator indicator = new ChromiumHealthIndicator(autoStart, props);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("cdpUrl")).isEqualTo("http://localhost:9222");
    }

    @Test
    void reportsDownWhenEnabledButNotRunning() {
        ChromiumAutoStart autoStart = mock(ChromiumAutoStart.class);
        when(autoStart.isRunning()).thenReturn(false);
        AgentProperties props = new AgentProperties();
        props.getChromium().setAutoStart(true);

        ChromiumHealthIndicator indicator = new ChromiumHealthIndicator(autoStart, props);
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void reportsUpWithNotConfiguredWhenAutoStartDisabled() {
        ChromiumAutoStart autoStart = mock(ChromiumAutoStart.class);
        AgentProperties props = new AgentProperties();
        props.getChromium().setAutoStart(false);

        ChromiumHealthIndicator indicator = new ChromiumHealthIndicator(autoStart, props);
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        assertThat(health.getDetails()).containsEntry("reason", "Chromium auto-start is disabled");
    }
}