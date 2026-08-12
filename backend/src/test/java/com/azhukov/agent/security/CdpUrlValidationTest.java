package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.azhukov.agent.core.security.UrlSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CdpUrlValidationTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setUrlSafetyEnabled(true);
        return p;
    }

    private UrlSafetyHandler handler(AgentProperties p) {
        return new UrlSafetyHandler(p, new DefaultUrlSafety(p));
    }

    // ── Valid ws:// and wss:// URLs ──

    @Test
    void validWssUrlPasses() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("wss://example.com:9222")).isNull();
    }

    @Test
    void validWsUrlPassesWhenSafetyDisabled() {
        AgentProperties p = props();
        p.getSecurity().setUrlSafetyEnabled(false);
        UrlSafetyHandler h = handler(p);
        assertThat(h.validate("ws://localhost:9222")).isNull();
    }

    @Test
    void validWssUrlWithPathPasses() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("wss://example.com:9222/devtools/browser/abc123")).isNull();
    }

    // ── Valid http:// and https:// CDP URLs (DevTools HTTP endpoint) ──

    @Test
    void validHttpCdpUrlPasses() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("http://example.com:9222")).isNull();
    }

    @Test
    void validHttpsCdpUrlPasses() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("https://example.com:9222")).isNull();
    }

    @Test
    void validHttpCdpUrlWithPathPasses() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("http://example.com:9222/json/list")).isNull();
    }

    // ── Localhost and loopback always allowed for CDP ──

    @Test
    void allowsLocalhostWsWhenSafetyEnabled() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("ws://localhost:9222")).isNull();
    }

    @Test
    void allowsLocalhostHttpWhenSafetyEnabled() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("http://localhost:9222")).isNull();
    }

    @Test
    void allowsLocalhostLocaldomainWhenSafetyEnabled() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("ws://localhost.localdomain:9222")).isNull();
    }

    @Test
    void allowsLoopbackIp127_0_0_1Ws() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("ws://127.0.0.1:9222")).isNull();
    }

    @Test
    void allowsLoopbackIp127_0_0_1Http() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("http://127.0.0.1:9222")).isNull();
    }

    @Test
    void allowsLoopbackIp127_1_2_3() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("ws://127.1.2.3:9222")).isNull();
    }

    @Test
    void allowsIpv6Loopback() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("ws://[::1]:9222")).isNull();
    }

    @Test
    void allowsUnspecifiedAddress0000() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("http://0.0.0.0:9222")).isNull();
    }

    @Test
    void allowsLocalhostWhenSafetyDisabled() {
        AgentProperties p = props();
        p.getSecurity().setUrlSafetyEnabled(false);
        UrlSafetyHandler h = handler(p);
        assertThat(h.validate("ws://localhost:9222")).isNull();
    }

    // ── Scheme validation ──

    @Test
    void rejectsFtpScheme() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("ftp://example.com");
        assertThat(error).contains("ws://").contains("wss://");
    }

    @Test
    void rejectsFileScheme() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("file:///etc/passwd");
        assertThat(error).contains("ws://").contains("wss://");
    }

    @Test
    void rejectsSchemeCaseInsensitive() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("FTP://example.com");
        assertThat(error).contains("ws://");
    }

    // ── Empty / null ──

    @Test
    void rejectsNullUrl() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate(null)).isEqualTo("cdpUrl is empty");
    }

    @Test
    void rejectsBlankUrl() {
        UrlSafetyHandler h = handler(props());
        assertThat(h.validate("")).isEqualTo("cdpUrl is empty");
        assertThat(h.validate("   ")).isEqualTo("cdpUrl is empty");
    }

    // ── Malformed URLs ──

    @Test
    void rejectsMalformedUrl() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("ws://[invalid");
        assertThat(error).contains("Invalid cdpUrl");
    }

    @Test
    void rejectsUrlWithoutHost() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("ws:///path");
        assertThat(error).contains("host is missing");
    }

    // ── Credential injection ──

    @Test
    void rejectsUrlWithEmbeddedCredentials() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("wss://admin:password@example.com:9222");
        assertThat(error).contains("embedded credentials");
    }

    @Test
    void rejectsLocalhostWithEmbeddedCredentials() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("ws://admin:password@localhost:9222");
        assertThat(error).contains("embedded credentials");
    }

    // ── SSRF protection (metadata, configured blocked hosts) ──

    @Test
    void rejectsMetadataEndpoint() {
        UrlSafetyHandler h = handler(props());
        String error = h.validate("ws://metadata.google.internal:9222");
        assertThat(error).contains("metadata").contains("blocked");
    }

    @Test
    void rejectsConfiguredBlockedHost() {
        AgentProperties p = props();
        p.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        UrlSafetyHandler h = handler(p);
        String error = h.validate("wss://evil.com:9222");
        assertThat(error).contains("blocked");
    }

    @Test
    void rejectsBlockedDomain() {
        AgentProperties p = props();
        p.getWeb().getBlockedDomains().add("banned.com");
        UrlSafetyHandler h = handler(p);
        String error = h.validate("wss://banned.com:9222");
        assertThat(error).contains("blocked");
    }

    @Test
    void rejectsBlockedHostEvenForLocalhost() {
        AgentProperties p = props();
        p.getSecurity().setBlockedUrlHosts(List.of("localhost"));
        UrlSafetyHandler h = handler(p);
        String error = h.validate("ws://localhost:9222");
        assertThat(error).contains("blocked");
    }
}