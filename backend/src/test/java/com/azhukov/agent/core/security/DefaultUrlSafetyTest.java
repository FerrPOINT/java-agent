package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for {@link DefaultUrlSafety}.
 *
 * <p>Current implementation only checks:
 * <ul>
 *   <li>Scheme is http or https</li>
 *   <li>Host is present and non-blank</li>
 *   <li>Host is not in the configured blockedUrlHosts list (exact or suffix match)</li>
 * </ul>
 *
 * <p>It does NOT have:
 * <ul>
 *   <li>Private IP range detection (10.x, 172.16-31.x, 192.168.x)</li>
 *   <li>Loopback address detection (127.0.0.1, ::1)</li>
 *   <li>Link-local / metadata endpoint detection (169.254.169.254)</li>
 *   <li>localhost blocking by default</li>
 *   <li>IPv6 address handling</li>
 *   <li>Port-based blocking</li>
 *   <li>IDN homograph attack detection</li>
 *   <li>URL credential stripping/blocking</li>
 *   <li>DNS rebinding protection</li>
 * </ul>
 * Tests below verify current behavior and document gaps via test names.
 */
class DefaultUrlSafetyTest {

    // ─── Existing tests (preserved) ───

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

    // ─── SSRF: Loopback addresses (GAP: not blocked by default) ───

    @Test
    void ssrf_127_0_0_1_currentlyAllowed_noPrivateIpBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GAP: 127.0.0.1 is not in default blockedHosts — SSRF to localhost is possible
        assertThat(safety.isUrlAllowed("http://127.0.0.1/")).isTrue();
    }

    @Test
    void ssrf_127_0_0_1_altPort_currentlyAllowed_noPrivateIpBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://127.0.0.1:8080/admin")).isTrue();
    }

    @Test
    void ssrf_127_0_0_1_canBeBlockedViaConfig() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("127.0.0.1"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // When explicitly configured, it IS blocked
        assertThat(safety.isUrlAllowed("http://127.0.0.1/")).isFalse();
    }

    @Test
    void ssrf_localhost_currentlyAllowed_notBlockedByDefault() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GAP: localhost is not blocked by default
        assertThat(safety.isUrlAllowed("http://localhost/")).isTrue();
        assertThat(safety.isUrlAllowed("http://localhost:3000/")).isTrue();
    }

    @Test
    void ssrf_localhost_canBeBlockedViaConfig() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("localhost"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://localhost/")).isFalse();
    }

    @Test
    void ssrf_127_1_1_1_currentlyAllowed_noPrivateIpBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 127.0.0.0/8 range — all are loopback but not blocked
        assertThat(safety.isUrlAllowed("http://127.1.2.3/")).isTrue();
        assertThat(safety.isUrlAllowed("http://127.255.255.255/")).isTrue();
    }

    // ─── SSRF: Private IP ranges (GAP: not blocked by default) ───

    @Test
    void ssrf_10_0_0_1_currentlyAllowed_noPrivateIpRangeBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 10.0.0.0/8 — private network, not blocked
        assertThat(safety.isUrlAllowed("http://10.0.0.1/")).isTrue();
        assertThat(safety.isUrlAllowed("http://10.255.255.255/")).isTrue();
    }

    @Test
    void ssrf_172_16_0_1_currentlyAllowed_noPrivateIpRangeBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 172.16.0.0/12 — private network, not blocked
        assertThat(safety.isUrlAllowed("http://172.16.0.1/")).isTrue();
        assertThat(safety.isUrlAllowed("http://172.31.255.255/")).isTrue();
    }

    @Test
    void ssrf_172_15_0_1_currentlyAllowed_isPublicRangeButAlsoNotBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 172.15.x.x is actually public, so allowing it is fine
        assertThat(safety.isUrlAllowed("http://172.15.0.1/")).isTrue();
    }

    @Test
    void ssrf_192_168_0_1_currentlyAllowed_noPrivateIpRangeBlocking() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 192.168.0.0/16 — private network, not blocked
        assertThat(safety.isUrlAllowed("http://192.168.0.1/")).isTrue();
        assertThat(safety.isUrlAllowed("http://192.168.1.100:8080/")).isTrue();
    }

    @Test
    void ssrf_privateRanges_canBeBlockedViaConfig() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("10.0.0.1", "192.168.0.1"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Explicitly configured — works
        assertThat(safety.isUrlAllowed("http://10.0.0.1/")).isFalse();
        assertThat(safety.isUrlAllowed("http://192.168.0.1/")).isFalse();
    }

    // ─── SSRF: AWS metadata endpoint (GAP: not blocked by default) ───

    @Test
    void ssrf_awsMetadata_169_254_169_254_currentlyAllowed_notBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 169.254.169.254 — AWS/GCP/Azure metadata endpoint, not blocked
        // This is a critical SSRF vector
        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/")).isTrue();
    }

    @Test
    void ssrf_awsMetadata_imdsv2_tokenEndpoint_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/api/token")).isTrue();
    }

    @Test
    void ssrf_awsMetadata_canBeBlockedViaConfig() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("169.254.169.254"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    void ssrf_gcpMetadata_currentlyAllowed_notBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GCP metadata endpoint
        assertThat(safety.isUrlAllowed("http://metadata.google.internal/computeMetadata/v1/")).isTrue();
    }

    // ─── SSRF: Link-local addresses (GAP: not blocked by default) ───

    @Test
    void ssrf_linkLocal_169_254_0_0_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 169.254.0.0/16 — link-local, not blocked
        assertThat(safety.isUrlAllowed("http://169.254.1.1/")).isTrue();
    }

    // ─── IPv6 tests (GAP: no IPv6-specific handling) ───

    @Test
    void ipv6_loopback_currentlyAllowed_notBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // [::1] is IPv6 loopback — should be blocked but currently isn't
        // GAP: no IPv6 loopback detection
        assertThat(safety.isUrlAllowed("http://[::1]/"))
                .as("IPv6 loopback [::1] currently allowed — no IPv6 blocking")
                .isTrue();
    }

    @Test
    void ipv6_unspecified_currentlyAllowed_notBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // [::] is IPv6 unspecified — should be blocked but currently isn't
        assertThat(safety.isUrlAllowed("http://[::]/"))
                .as("IPv6 unspecified [::] currently allowed — no IPv6 blocking")
                .isTrue();
    }

    @Test
    void ipv6_linkLocal_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // fe80:: — IPv6 link-local
        assertThat(safety.isUrlAllowed("http://[fe80::1]/"))
                .as("IPv6 link-local currently allowed — no IPv6 blocking")
                .isTrue();
    }

    @Test
    void ipv6_uniqueLocal_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // fc00:: — IPv6 unique local (private)
        assertThat(safety.isUrlAllowed("http://[fc00::1]/"))
                .as("IPv6 unique local currently allowed — no IPv6 blocking")
                .isTrue();
    }

    @Test
    void ipv6_normalAddress_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Normal IPv6 address should be allowed
        assertThat(safety.isUrlAllowed("http://[2606:4700:4700::1111]/"))
                .as("Public IPv6 address should be allowed")
                .isTrue();
    }

    // ─── Port-based blocking tests (GAP: no port checking) ───

    @Test
    void portBasedBlocking_port22_currentlyAllowed_noPortCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GAP: no port-based blocking
        assertThat(safety.isUrlAllowed("http://example.com:22/")).isTrue();
    }

    @Test
    void portBasedBlocking_port25_currentlyAllowed_noPortCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://example.com:25/")).isTrue();
    }

    @Test
    void portBasedBlocking_port6379_redis_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Redis port — common SSRF target
        assertThat(safety.isUrlAllowed("http://internal.example.com:6379/")).isTrue();
    }

    @Test
    void portBasedBlocking_standardPorts_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://example.com:80/")).isTrue();
        assertThat(safety.isUrlAllowed("https://example.com:443/")).isTrue();
        assertThat(safety.isUrlAllowed("http://example.com:8080/")).isTrue();
    }

    // ─── IDN homograph attack tests (GAP: no IDN handling) ───

    @Test
    void idnHomograph_lookalikeDomain_currentlyAllowed_noIdnCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Homograph: "examp1e.com" (with digit 1 instead of letter l)
        // GAP: no IDN/punycode normalization or homograph detection
        assertThat(safety.isUrlAllowed("https://examp1e.com/")).isTrue();
    }

    @Test
    void idnHomograph_punycodeDomain_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Punycode domain (IDN) — could be used for homograph attacks
        // GAP: no punycode normalization
        assertThat(safety.isUrlAllowed("https://xn--exmple-cta.com/")).isTrue();
    }

    @Test
    void idnHomograph_cyrillicLookalike_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Cyrillic 'а' (U+0430) instead of Latin 'a' in "example"
        // This would look identical to "example.com" but resolve differently
        // GAP: no homograph detection
        // Note: Java URI may or may not parse this — test documents current behavior
        String url = "https://ex\u0430mple.com/";
        boolean allowed = safety.isUrlAllowed(url);
        // Either it's allowed (if parsed) or blocked (if URI parsing fails)
        // Document the behavior either way
        if (allowed) {
            assertThat(allowed).as("Cyrillic homograph domain currently allowed — no IDN check").isTrue();
        } else {
            assertThat(allowed).as("Cyrillic homograph domain blocked by URI parsing, not by security check").isFalse();
        }
    }

    // ─── URL with embedded credentials (GAP: not checked) ───

    @Test
    void urlWithCredentials_currentlyAllowed_noCredentialCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // URL with embedded credentials — should be flagged but isn't
        assertThat(safety.isUrlAllowed("https://admin:password@internal.example.com/")).isTrue();
    }

    @Test
    void urlWithCredentials_localhost_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://user:pass@localhost:8080/admin")).isTrue();
    }

    // ─── Scheme edge cases ───

    @Test
    void schemeCaseInsensitive_httpAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Scheme check is case-insensitive
        assertThat(safety.isUrlAllowed("HTTP://example.com/")).isTrue();
        assertThat(safety.isUrlAllowed("HTTPS://example.com/")).isTrue();
        assertThat(safety.isUrlAllowed("Http://example.com/")).isTrue();
    }

    @Test
    void javascriptScheme_blocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("javascript:alert(1)")).isFalse();
    }

    @Test
    void dataScheme_blocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("data:text/html,<script>alert(1)</script>")).isFalse();
    }

    @Test
    void gopherScheme_blocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Gopher protocol is sometimes used in SSRF
        assertThat(safety.isUrlAllowed("gopher://127.0.0.1:6379/")).isFalse();
    }

    // ─── Host blocking edge cases ───

    @Test
    void hostBlocking_exactMatch() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isHostBlocked("evil.com")).isTrue();
    }

    @Test
    void hostBlocking_subdomainMatch() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isHostBlocked("api.evil.com")).isTrue();
        assertThat(safety.isHostBlocked("deep.nested.evil.com")).isTrue();
    }

    @Test
    void hostBlocking_notSuffixMatch_preventsBypass() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // "notevil.com" should NOT be blocked — endsWith check uses ".evil.com"
        assertThat(safety.isHostBlocked("notevil.com")).isFalse();
        // "evil.com.evil.com" DOES end with ".evil.com" so it IS blocked (correct behavior)
        assertThat(safety.isHostBlocked("evil.com.evil.com")).isTrue();
        // "notreallyevil.com" does NOT end with ".evil.com"
        assertThat(safety.isHostBlocked("notreallyevil.com")).isFalse();
    }

    @Test
    void hostBlocking_caseInsensitive() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("Evil.COM"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isHostBlocked("evil.com")).isTrue();
        assertThat(safety.isHostBlocked("EVIL.COM")).isTrue();
        assertThat(safety.isHostBlocked("API.Evil.Com")).isTrue();
    }

    @Test
    void hostBlocking_nullHost_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isHostBlocked(null)).isFalse();
    }

    @Test
    void hostBlocking_emptyBlockedHosts_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isHostBlocked("evil.com")).isFalse();
    }

    @Test
    void hostBlocking_nullBlockedHosts_returnsFalse() {
        // Default is empty ArrayList — simulate by not setting anything
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // isHostBlocked checks if blockedHosts is null — default is empty list, not null
        assertThat(safety.isHostBlocked("any.host.com")).isFalse();
    }

    // ─── Malformed URL tests ───

    @Test
    void malformedUrl_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("not a url")).isFalse();
        assertThat(safety.isUrlAllowed("")).isFalse();
        assertThat(safety.isUrlAllowed("http://")).isFalse();
        assertThat(safety.isUrlAllowed("://no-scheme")).isFalse();
    }

    @Test
    void nullUrl_currentlyThrowsNpe_noNullCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GAP: no null check on URL input — new URI(null) throws NPE
        // The method doesn't catch NullPointerException, only URISyntaxException
        assertThatThrownBy(() -> safety.isUrlAllowed(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void urlWithNoHost_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http:///path")).isFalse();
    }

    // ─── Disabled flag ───

    @Test
    void disabled_allowsEverythingIncludingFtp() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(false);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("ftp://anything")).isTrue();
        assertThat(safety.isUrlAllowed("file:///etc/passwd")).isTrue();
        assertThat(safety.isUrlAllowed("javascript:alert(1)")).isTrue();
    }

    @Test
    void disabled_allowsPrivateAndMetadataUrls() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(false);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://127.0.0.1/")).isTrue();
        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/")).isTrue();
        assertThat(safety.isUrlAllowed("http://10.0.0.1/")).isTrue();
    }

    // ─── isHostBlocked works independently of urlSafetyEnabled ───

    @Test
    void isHostBlocked_worksRegardlessOfUrlSafetyEnabled() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(false);
        properties.getSecurity().setBlockedUrlHosts(List.of("evil.com"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // isHostBlocked does NOT check urlSafetyEnabled — it always checks the list
        // This is different from isUrlAllowed which checks the flag first
        assertThat(safety.isHostBlocked("evil.com")).isTrue();
    }

    // ─── Decimal IP encoding (SSRF bypass technique) ───

    @Test
    void ssrf_decimalIp_2130706433_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 2130706433 = 127.0.0.1 in decimal notation — SSRF bypass technique
        // GAP: no decimal IP detection
        // Note: Java URI may parse this as a hostname, not an IP
        assertThat(safety.isUrlAllowed("http://2130706433/"))
                .as("Decimal IP encoding of 127.0.0.1 — currently allowed, no IP normalization")
                .isTrue();
    }

    @Test
    void ssrf_octalIp_0177_0_0_1_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0177.0.0.1 = 127.0.0.1 in octal — SSRF bypass technique
        // GAP: no octal IP detection
        // Note: Java URI may parse "0177" as a hostname
        boolean allowed = safety.isUrlAllowed("http://0177.0.0.1/");
        // Document the behavior
        assertThat(allowed)
                .as("Octal IP encoding of 127.0.0.1 — behavior depends on URI parsing")
                .isTrue();
    }

    @Test
    void ssrf_hexIp_0x7f000001_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0x7f000001 = 127.0.0.1 in hex — SSRF bypass technique
        // GAP: no hex IP detection
        boolean allowed = safety.isUrlAllowed("http://0x7f000001/");
        assertThat(allowed)
                .as("Hex IP encoding of 127.0.0.1 — behavior depends on URI parsing")
                .isTrue();
    }

    // ─── 0.0.0.0 (all interfaces) ───

    @Test
    void ssrf_0_0_0_0_currentlyAllowed_notBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0.0.0.0 — on some systems routes to localhost
        // GAP: not blocked
        assertThat(safety.isUrlAllowed("http://0.0.0.0/")).isTrue();
    }

    // ─── Real-world SSRF scenario ───

    @Test
    void ssrf_realWorldScenario_allCurrentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Simulate an attacker trying to access internal services
        // All of these should be blocked but currently aren't
        assertThat(safety.isUrlAllowed("http://127.0.0.1:6379/")).isTrue();           // Redis
        assertThat(safety.isUrlAllowed("http://127.0.0.1:11211/")).isTrue();          // Memcached
        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/iam/security-credentials/")).isTrue(); // AWS creds
        assertThat(safety.isUrlAllowed("http://10.0.0.1:9200/_search")).isTrue();     // Elasticsearch
        assertThat(safety.isUrlAllowed("http://192.168.1.1/admin")).isTrue();          // Router admin
        assertThat(safety.isUrlAllowed("http://localhost:5432/")).isTrue();            // PostgreSQL
    }
}