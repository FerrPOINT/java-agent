package com.azhukov.agent.client.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-e (Hermes d9e88e19e2 parity, V67): stored OAuth refresh tokens are
 * bound to their issuer; the refresh path derives the current issuer from
 * the configured token endpoint.
 */
class McpOAuthIssuerBindingTest {

    @Test
    void issuerIsEndpointOrigin() {
        assertThat(McpOAuthManager.issuerOf("https://auth.example.com/oauth/token"))
            .isEqualTo("https://auth.example.com");
        assertThat(McpOAuthManager.issuerOf("https://auth.example.com:8443/token"))
            .isEqualTo("https://auth.example.com:8443");
        assertThat(McpOAuthManager.issuerOf("http://localhost:3333/mcp/oauth/token"))
            .isEqualTo("http://localhost:3333");
    }

    @Test
    void sameOriginDifferentPathsIsSameIssuer() {
        // Path is NOT part of the identity — only scheme/host/port.
        assertThat(McpOAuthManager.issuerOf("https://auth.example.com/v2/token"))
            .isEqualTo(McpOAuthManager.issuerOf("https://auth.example.com/v1/token"));
    }

    @Test
    void differentHostsAreDifferentIssuers() {
        assertThat(McpOAuthManager.issuerOf("https://auth.example.com/token"))
            .isNotEqualTo(McpOAuthManager.issuerOf("https://auth.other.com/token"));
    }

    @Test
    void portDifferenceIsADifferentIssuer() {
        assertThat(McpOAuthManager.issuerOf("https://auth.example.com/token"))
            .isNotEqualTo(McpOAuthManager.issuerOf("https://auth.example.com:9443/token"));
    }

    @Test
    void unparseableUrlFallsBackToRawString() {
        assertThat(McpOAuthManager.issuerOf("not a url at all"))
            .isEqualTo("not a url at all");
    }
}
