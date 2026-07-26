package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.browser.CdpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BrowserHealthIndicatorTest {

    @Test
    void upWhenConnected() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenReturn(true);
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, new AgentProperties());
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.UP);
    }

    @Test
    void downWhenNotConnected() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenReturn(false);
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, new AgentProperties());
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
    }

    @Test
    void downOnException() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenThrow(new RuntimeException("boom"));
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, new AgentProperties());
        assertThat(h.health().getStatus()).isEqualTo(org.springframework.boot.health.contributor.Status.DOWN);
    }
}
