package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UrlSafetyHandlerTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setUrlSafetyEnabled(true);
        return p;
    }

    @Test
    void allowsHttpsUrl() {
        UrlSafetyHandler h = new UrlSafetyHandler(props());
        assertThat(h.checkUrl("https://example.com")).isNull();
    }

    @Test
    void blocksHttpUrl() {
        UrlSafetyHandler h = new UrlSafetyHandler(props());
        assertThat(h.checkUrl("http://example.com")).contains("Insecure transport");
    }

    @Test
    void blocksEmptyUrl() {
        UrlSafetyHandler h = new UrlSafetyHandler(props());
        assertThat(h.checkUrl("")).isEqualTo("URL is empty");
        assertThat(h.checkUrl(null)).isEqualTo("URL is empty");
    }

    @Test
    void blocksInvalidScheme() {
        UrlSafetyHandler h = new UrlSafetyHandler(props());
        assertThat(h.checkUrl("ftp://example.com")).isEqualTo("Only http/https URLs are allowed");
    }

    @Test
    void blocksConfiguredHost() {
        AgentProperties p = props();
        p.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        UrlSafetyHandler h = new UrlSafetyHandler(p);
        assertThat(h.checkUrl("https://evil.com")).contains("Host is blocked");
        assertThat(h.checkUrl("https://sub.evil.com")).contains("Host is blocked");
    }

    @Test
    void blocksPrivateAddress() {
        UrlSafetyHandler h = new UrlSafetyHandler(props());
        assertThat(h.checkUrl("https://127.0.0.1")).contains("Private/loopback");
        assertThat(h.checkUrl("https://192.168.1.1")).contains("Private/loopback");
    }

    @Test
    void disabledSafetyAllowsHttp() {
        AgentProperties p = props();
        p.getSecurity().setUrlSafetyEnabled(false);
        UrlSafetyHandler h = new UrlSafetyHandler(p);
        assertThat(h.checkUrl("http://example.com")).isNull();
    }
}
