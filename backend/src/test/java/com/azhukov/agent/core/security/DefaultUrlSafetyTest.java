package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUrlSafetyTest {

    @Test
    void allowsHttpAndHttps() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);
        assertThat(safety.isUrlAllowed("https://example.com/path")).isTrue();
        assertThat(safety.isUrlAllowed("http://localhost:8080")).isTrue();
    }

    @Test
    void blocksInvalidAndNonHttpSchemes() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);
        assertThat(safety.isUrlAllowed("ftp://example.com/file")).isFalse();
        assertThat(safety.isUrlAllowed("file:///etc/passwd")).isFalse();
        assertThat(safety.isUrlAllowed("not a url")).isFalse();
    }

    @Test
    void blocksHostsInBlockedList() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com", "internal.local"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);
        assertThat(safety.isUrlAllowed("https://evil.com/login")).isFalse();
        assertThat(safety.isUrlAllowed("https://api.evil.com/login")).isFalse();
        assertThat(safety.isUrlAllowed("https://internal.local/x")).isFalse();
        assertThat(safety.isUrlAllowed("https://example.com/")).isTrue();
    }

    @Test
    void skipsCheckWhenDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(false);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);
        assertThat(safety.isUrlAllowed("ftp://anything")).isTrue();
    }
}
