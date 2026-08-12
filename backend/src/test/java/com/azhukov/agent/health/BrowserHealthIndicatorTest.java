package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.tools.browser.CdpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BrowserHealthIndicatorTest {

    @Test
    void upWhenConnected() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenReturn(true);
        AgentProperties props = new AgentProperties();
        props.getBrowser().setCdpUrl("http://localhost:9222");
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("cdpUrl", "http://localhost:9222");
    }

    @Test
    void downWhenNotConnected() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenReturn(false);
        AgentProperties props = new AgentProperties();
        props.getBrowser().setCdpUrl("http://localhost:9222");
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downOnException() throws Exception {
        CdpClient c = mock(CdpClient.class);
        when(c.isConnected()).thenThrow(new RuntimeException("boom"));
        AgentProperties props = new AgentProperties();
        props.getBrowser().setCdpUrl("http://localhost:9222");
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void upWithNotConfiguredWhenCdpUrlIsBlank() {
        CdpClient c = mock(CdpClient.class);
        AgentProperties props = new AgentProperties();
        props.getBrowser().setCdpUrl("");
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        verifyNoInteractions(c);
    }

    @Test
    void upWithNotConfiguredWhenCdpUrlIsNull() {
        CdpClient c = mock(CdpClient.class);
        AgentProperties props = new AgentProperties();
        props.getBrowser().setCdpUrl(null);
        BrowserHealthIndicator h = new BrowserHealthIndicator(c, props);
        Health health = h.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "not_configured");
        verifyNoInteractions(c);
    }
}