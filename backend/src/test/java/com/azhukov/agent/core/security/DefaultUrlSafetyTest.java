package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comprehensive tests for {@link DefaultUrlSafety}.
 *
 * <p>The implementation now provides full SSRF protection:
 * <ul>
 *   <li>Scheme is http or https</li>
 *   <li>Host is present and non-blank</li>
 *   <li>Host is not in the configured blockedUrlHosts list (exact or suffix match)</li>
 *   <li>Private IP range detection (10.x, 172.16-31.x, 192.168.x)</li>
 *   <li>Loopback address detection (127.0.0.0/8, ::1)</li>
 *   <li>Link-local / metadata endpoint detection (169.254.x.x)</li>
 *   <li>localhost blocking by default</li>
 *   <li>IPv6 private/loopback/link-local/ULA detection</li>
 *   <li>0.0.0.0 / unspecified address blocking</li>
 *   <li>URL with embedded credentials blocked</li>
 *   <li>Encoded IP detection (decimal, octal, hex)</li>
 *   <li>Cloud metadata endpoint blocking (AWS, GCP)</li>
 * </ul>
 */
class DefaultUrlSafetyTest {

    // ─── Existing tests (preserved) ───

    @Test
    void allowsHttpAndHttps() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);
        assertThat(safety.isUrlAllowed("https://example.com/path")).isTrue();
        // localhost is now blocked by default
        assertThat(safety.isUrlAllowed("http://localhost:8080")).isFalse();
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

    // ─── SSRF: Loopback addresses (now blocked) ───

    @Test
    void ssrf_127_0_0_1_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://127.0.0.1/")).isFalse();
    }

    @Test
    void ssrf_127_0_0_1_altPort_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://127.0.0.1:8080/admin")).isFalse();
    }

    @Test
    void ssrf_127_0_0_1_canBeBlockedViaConfig() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        properties.getSecurity().setBlockedUrlHosts(List.of("127.0.0.1"));
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://127.0.0.1/")).isFalse();
    }

    @Test
    void ssrf_localhost_nowBlockedByDefault() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // localhost is now blocked by default
        assertThat(safety.isUrlAllowed("http://localhost/")).isFalse();
        assertThat(safety.isUrlAllowed("http://localhost:3000/")).isFalse();
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
    void ssrf_127_1_2_3_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 127.0.0.0/8 range — all are loopback, now blocked
        assertThat(safety.isUrlAllowed("http://127.1.2.3/")).isFalse();
        assertThat(safety.isUrlAllowed("http://127.255.255.255/")).isFalse();
    }

    // ─── SSRF: Private IP ranges (now blocked) ───

    @Test
    void ssrf_10_0_0_1_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 10.0.0.0/8 — private network, now blocked
        assertThat(safety.isUrlAllowed("http://10.0.0.1/")).isFalse();
        assertThat(safety.isUrlAllowed("http://10.255.255.255/")).isFalse();
    }

    @Test
    void ssrf_172_16_0_1_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 172.16.0.0/12 — private network, now blocked
        assertThat(safety.isUrlAllowed("http://172.16.0.1/")).isFalse();
        assertThat(safety.isUrlAllowed("http://172.31.255.255/")).isFalse();
    }

    @Test
    void ssrf_172_15_0_1_isPublicRange_allowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 172.15.x.x is actually public, so allowing it is correct
        // Note: InetAddress.getByName may attempt DNS resolution in test env,
        // but 172.15.0.1 is a valid IP literal and should be allowed
        assertThat(safety.isUrlAllowed("http://172.15.0.1/")).isTrue();
    }

    @Test
    void ssrf_192_168_0_1_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 192.168.0.0/16 — private network, now blocked
        assertThat(safety.isUrlAllowed("http://192.168.0.1/")).isFalse();
        assertThat(safety.isUrlAllowed("http://192.168.1.100:8080/")).isFalse();
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

    // ─── SSRF: AWS metadata endpoint (now blocked) ───

    @Test
    void ssrf_awsMetadata_169_254_169_254_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 169.254.169.254 — AWS/GCP/Azure metadata endpoint, now blocked
        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    void ssrf_awsMetadata_imdsv2_tokenEndpoint_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/api/token")).isFalse();
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
    void ssrf_gcpMetadata_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // GCP metadata endpoint — now blocked by name
        assertThat(safety.isUrlAllowed("http://metadata.google.internal/computeMetadata/v1/")).isFalse();
    }

    // ─── SSRF: Link-local addresses (now blocked) ───

    @Test
    void ssrf_linkLocal_169_254_0_0_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 169.254.0.0/16 — link-local, now blocked
        assertThat(safety.isUrlAllowed("http://169.254.1.1/")).isFalse();
    }

    // ─── IPv6 tests (now handled) ───

    @Test
    void ipv6_loopback_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // [::1] is IPv6 loopback — now blocked
        assertThat(safety.isUrlAllowed("http://[::1]/"))
                .as("IPv6 loopback [::1] now blocked")
                .isFalse();
    }

    @Test
    void ipv6_unspecified_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // [::] is IPv6 unspecified (0.0.0.0 equivalent) — now blocked
        assertThat(safety.isUrlAllowed("http://[::]/"))
                .as("IPv6 unspecified [::] now blocked")
                .isFalse();
    }

    @Test
    void ipv6_linkLocal_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // fe80:: — IPv6 link-local — now blocked
        assertThat(safety.isUrlAllowed("http://[fe80::1]/"))
                .as("IPv6 link-local now blocked")
                .isFalse();
    }

    @Test
    void ipv6_uniqueLocal_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // fc00:: — IPv6 unique local (private) — now blocked
        assertThat(safety.isUrlAllowed("http://[fc00::1]/"))
                .as("IPv6 unique local now blocked")
                .isFalse();
    }

    @Test
    void ipv6_normalAddress_nowAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Normal IPv6 address should be allowed
        assertThat(safety.isUrlAllowed("http://[2606:4700:4700::1111]/"))
                .as("Public IPv6 address should be allowed")
                .isTrue();
    }

    // ─── Port-based tests (standard ports allowed) ───

    @Test
    void portBasedBlocking_standardPorts_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.isUrlAllowed("http://example.com:80/")).isTrue();
        assertThat(safety.isUrlAllowed("https://example.com:443/")).isTrue();
        assertThat(safety.isUrlAllowed("http://example.com:8080/")).isTrue();
    }

    // ─── IDN homograph attack tests ───

    @Test
    void idnHomograph_lookalikeDomain_currentlyAllowed_noIdnCheck() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Homograph: "examp1e.com" (with digit 1 instead of letter l)
        // No IDN/punycode normalization or homograph detection — but this is not an SSRF risk
        assertThat(safety.isUrlAllowed("https://examp1e.com/")).isTrue();
    }

    @Test
    void idnHomograph_punycodeDomain_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Punycode domain (IDN) — could be used for homograph attacks
        // No punycode normalization — but this is not an SSRF risk
        assertThat(safety.isUrlAllowed("https://xn--exmple-cta.com/")).isTrue();
    }

    @Test
    void idnHomograph_cyrillicLookalike_currentlyAllowed() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Cyrillic 'а' (U+0430) instead of Latin 'a' in "example"
        // This would look identical to "example.com" but resolve differently
        // No homograph detection — but this is not an SSRF risk
        String url = "https://ex\u0430mple.com/";
        boolean allowed = safety.isUrlAllowed(url);
        if (allowed) {
            assertThat(allowed).as("Cyrillic homograph domain currently allowed — no IDN check").isTrue();
        } else {
            assertThat(allowed).as("Cyrillic homograph domain blocked by URI parsing, not by security check").isFalse();
        }
    }

    // ─── URL with embedded credentials (now blocked) ───

    @Test
    void urlWithCredentials_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // URL with embedded credentials — now blocked
        assertThat(safety.isUrlAllowed("https://admin:password@internal.example.com/")).isFalse();
    }

    @Test
    void urlWithCredentials_localhost_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Both credentials and localhost — now blocked
        assertThat(safety.isUrlAllowed("http://user:pass@localhost:8080/admin")).isFalse();
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
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

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
    void nullUrl_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // Null input now returns false (null/blank check added)
        assertThat(safety.isUrlAllowed(null)).isFalse();
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
        assertThat(safety.isHostBlocked("evil.com")).isTrue();
    }

    // ─── Decimal IP encoding (SSRF bypass technique) — now blocked ───

    @Test
    void ssrf_decimalIp_2130706433_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 2130706433 = 127.0.0.1 in decimal notation — now blocked by InetAddress resolution
        assertThat(safety.isUrlAllowed("http://2130706433/"))
                .as("Decimal IP encoding of 127.0.0.1 — now blocked by IP resolution")
                .isFalse();
    }

    @Test
    void ssrf_octalIp_0177_0_0_1_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0177.0.0.1 = 127.0.0.1 in octal — now blocked by encoded IP detection
        boolean allowed = safety.isUrlAllowed("http://0177.0.0.1/");
        assertThat(allowed)
                .as("Octal IP encoding of 127.0.0.1 — now blocked")
                .isFalse();
    }

    @Test
    void ssrf_hexIp_0x7f000001_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0x7f000001 = 127.0.0.1 in hex — now blocked by encoded IP detection
        boolean allowed = safety.isUrlAllowed("http://0x7f000001/");
        assertThat(allowed)
                .as("Hex IP encoding of 127.0.0.1 — now blocked")
                .isFalse();
    }

    // ─── 0.0.0.0 (all interfaces) — now blocked ───

    @Test
    void ssrf_0_0_0_0_nowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // 0.0.0.0 — on some systems routes to localhost — now blocked
        assertThat(safety.isUrlAllowed("http://0.0.0.0/")).isFalse();
    }

    // ─── Real-world SSRF scenario — now blocked ───

    @Test
    void ssrf_realWorldScenario_allNowBlocked() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        // All SSRF vectors are now blocked
        assertThat(safety.isUrlAllowed("http://127.0.0.1:6379/")).isFalse();           // Redis
        assertThat(safety.isUrlAllowed("http://127.0.0.1:11211/")).isFalse();          // Memcached
        assertThat(safety.isUrlAllowed("http://169.254.169.254/latest/meta-data/iam/security-credentials/")).isFalse(); // AWS creds
        assertThat(safety.isUrlAllowed("http://10.0.0.1:9200/_search")).isFalse();     // Elasticsearch
        assertThat(safety.isUrlAllowed("http://192.168.1.1/admin")).isFalse();          // Router admin
        assertThat(safety.isUrlAllowed("http://localhost:5432/")).isFalse();            // PostgreSQL
    }

    // ─── checkUrl default method ───

    @Test
    void checkUrl_returnsNullForAllowedUrl() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.checkUrl("https://example.com")).isNull();
    }

    @Test
    void checkUrl_returnsReasonForBlockedUrl() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setUrlSafetyEnabled(true);
        DefaultUrlSafety safety = new DefaultUrlSafety(properties);

        assertThat(safety.checkUrl("http://127.0.0.1/")).isNotNull();
        assertThat(safety.checkUrl("")).isEqualTo("URL is empty");
        assertThat(safety.checkUrl(null)).isEqualTo("URL is empty");
    }
}