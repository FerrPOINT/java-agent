package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.azhukov.agent.core.security.UrlSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UrlSafetyHandlerTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setUrlSafetyEnabled(true);
        return p;
    }

    private UrlSafetyHandler handler(AgentProperties p) {
        return new UrlSafetyHandler(p, new DefaultUrlSafety(p));
    }

    @Test
    void allowsHttpsUrl() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("https://example.com")).isNull();
    }

    @Test
    void blocksHttpUrl() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("http://example.com")).contains("Insecure transport");
    }

    @Test
    void blocksEmptyUrl() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("")).isEqualTo("URL is empty");
        assertThat(h.checkUrl(null)).isEqualTo("URL is empty");
    }

    @Test
    void blocksInvalidScheme() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("ftp://example.com")).isEqualTo("Only http/https URLs are allowed");
    }

    @Test
    void blocksConfiguredHost() {
        AgentProperties p = props();
        p.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        UrlSafetyHandler h = handler(p);
        assertThat(h.checkUrl("https://evil.com")).contains("blocked");
        assertThat(h.checkUrl("https://sub.evil.com")).contains("blocked");
    }

    @Test
    void blocksPrivateAddress() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("https://127.0.0.1")).contains("blocked");
        assertThat(h.checkUrl("https://192.168.1.1")).contains("blocked");
    }

    @Test
    void blocksLoopbackAndPrivateAndMetadata() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("https://127.0.0.1")).contains("blocked");
        assertThat(h.checkUrl("https://10.0.0.1")).contains("blocked");
        assertThat(h.checkUrl("https://169.254.169.254")).contains("blocked");
        assertThat(h.checkUrl("https://localhost")).contains("blocked");
    }

    @Test
    void blocksUrlWithCredentials() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.checkUrl("https://admin:password@example.com")).contains("embedded credentials");
    }

    @Test
    void disabledSafetyAllowsHttp() {
        AgentProperties p = props();
        p.getSecurity().setUrlSafetyEnabled(false);
        UrlSafetyHandler h = handler(p);
        assertThat(h.checkUrl("http://example.com")).isNull();
    }
}