package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardWebSocketGuardTest {

    private final AgentProperties properties = new AgentProperties();

    @Test
    void loopbackBoundAcceptsLoopbackHostAndOrigin() {
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("localhost:8090", "http://localhost:8090")).isNull();
    }

    @Test
    void loopbackBoundRejectsDnsRebindingHost() {
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("evil.example", "http://evil.example")).isEqualTo("host_mismatch");
    }

    @Test
    void trustedCorsPublicHostIsAcceptedExactly() {
        properties.getApi().setCorsOrigins(List.of("https://dashboard.example.test"));
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("dashboard.example.test:9443", "https://dashboard.example.test:9443")).isNull();
        assertThat(guard.rejectionReason("dashboard.example.test.evil.test", "https://dashboard.example.test.evil.test"))
            .isEqualTo("host_mismatch");
    }

    @Test
    void trustedPublicHostRejectsCrossSiteWebOrigin() {
        properties.getApi().setCorsOrigins(List.of("https://dashboard.example.test"));
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("dashboard.example.test:9443", "https://evil.test"))
            .isEqualTo("origin_mismatch");
    }

    @Test
    void nonWebOriginIsAllowedAfterAcceptedHostLikeHermesDesktop() {
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("localhost:8090", "file://dashboard/index.html")).isNull();
    }

    @Test
    void wildcardCorsDoesNotTrustPublicHostForWebSocketUpgrade() {
        properties.getApi().setCorsOrigins(List.of("*"));
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        assertThat(guard.rejectionReason("random.example", "https://random.example"))
            .isEqualTo("host_mismatch");
    }

    @Test
    void zeroZeroBindAcceptsAnyHostLikeHermesInsecureMode() {
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "0.0.0.0");

        assertThat(guard.rejectionReason("evil.example:8090", "https://evil.example:8090")).isNull();
    }

    @Test
    void malformedHostAuthoritiesFailClosed() {
        DashboardWebSocketGuard guard = new DashboardWebSocketGuard(properties, "127.0.0.1");

        for (String malformed : Set.of(
            "http://dashboard.example.test:9443",
            "dashboard.example.test:",
            "dashboard.example.test:notaport",
            "[::1].evil.test",
            "[::1]:notaport",
            "[localhost]")) {
            assertThat(guard.rejectionReason(malformed, null)).isEqualTo("host_mismatch");
        }
    }
}
