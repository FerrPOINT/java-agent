package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity (tools/url_safety.py): security.allow_private_urls permits
 * loopback/private/LAN targets, but cloud-metadata endpoints and the link-local
 * range stay blocked unconditionally.
 */
class DefaultUrlSafetyAllowPrivateTest {

    private DefaultUrlSafety safety(boolean allowPrivate) {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setAllowPrivateUrls(allowPrivate);
        return new DefaultUrlSafety(properties) {
            @Override
            InetAddress[] resolveAll(String host) throws java.net.UnknownHostException {
                // Deterministic resolution independent of the host's DNS.
                return switch (host) {
                    case "localhost" -> new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                    case "lan-host" -> new InetAddress[]{InetAddress.getByName("192.168.1.20")};
                    case "example.com" -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                    default -> super.resolveAll(host);
                };
            }
        };
    }

    @Test
    void localhostBlockedByDefault() {
        assertThat(safety(false).isUrlAllowed("http://localhost:8765/test.html")).isFalse();
    }

    @Test
    void localhostAllowedWhenPrivateUrlsPermitted() {
        assertThat(safety(true).isUrlAllowed("http://localhost:8765/test.html")).isTrue();
    }

    @Test
    void privateLanBlockedByDefault() {
        assertThat(safety(false).isUrlAllowed("http://lan-host:8080/")).isFalse();
        assertThat(safety(false).isUrlAllowed("http://192.168.1.20:8080/")).isFalse();
    }

    @Test
    void privateLanAllowedWhenPrivateUrlsPermitted() {
        assertThat(safety(true).isUrlAllowed("http://lan-host:8080/")).isTrue();
        assertThat(safety(true).isUrlAllowed("http://192.168.1.20:8080/")).isTrue();
    }

    @Test
    void publicUrlsUnaffectedByToggle() {
        assertThat(safety(false).isUrlAllowed("https://example.com")).isTrue();
        assertThat(safety(true).isUrlAllowed("https://example.com")).isTrue();
    }

    @Test
    void cloudMetadataStillBlockedWhenPrivateUrlsPermitted() {
        DefaultUrlSafety permissive = safety(true);
        assertThat(permissive.isUrlAllowed("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(permissive.isUrlAllowed("http://100.100.100.200/latest/meta-data/")).isFalse();
        assertThat(permissive.isUrlAllowed("http://metadata.google.internal/computeMetadata/v1/")).isFalse();
        assertThat(permissive.isUrlAllowed("http://metadata.goog/computeMetadata/v1/")).isFalse();
    }

    @Test
    void linkLocalRangeStillBlockedWhenPrivateUrlsPermitted() {
        assertThat(safety(true).isUrlAllowed("http://169.254.7.7/")).isFalse();
    }

    @Test
    void encodedPrivateIpAllowedUnderToggle() {
        // 0x7f000001 == 127.0.0.1 in hex form
        assertThat(safety(false).isUrlAllowed("http://0x7f000001/")).isFalse();
        assertThat(safety(true).isUrlAllowed("http://0x7f000001/")).isTrue();
    }
}
