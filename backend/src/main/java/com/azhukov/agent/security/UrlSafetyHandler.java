package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.security.UrlSafety;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlSafetyHandler {

    private final AgentProperties properties;
    private final UrlSafety urlSafety;

    public String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL is empty";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "Only http/https URLs are allowed";
        }
        if (uri.getHost() == null) {
            return "URL host is missing";
        }
        // Block URLs with embedded credentials
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return "URL with embedded credentials is not allowed: " + uri.getHost();
        }
        if (!properties.getSecurity().isUrlSafetyEnabled()) {
            return null;
        }
        // Delegate to UrlSafety for comprehensive checks (IP resolution, private ranges, etc.)
        if (!urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy: " + uri.getHost();
        }
        // Check web.blockedDomains (separate from security.blockedUrlHosts)
        if (properties.getWeb().getBlockedDomains().contains(uri.getHost())) {
            return "Domain is blocked: " + uri.getHost();
        }
        // Enforce HTTPS only when safety is enabled
        if (!"https".equalsIgnoreCase(scheme)) {
            return "Insecure transport (http) not allowed";
        }
        return null;
    }

    /**
     * Validates a Chrome DevTools Protocol (CDP) URL.
     * <p>
     * CDP uses either WebSocket transport ({@code ws://} or {@code wss://}) or
     * HTTP transport ({@code http://} or {@code https://}) for the DevTools
     * HTTP endpoint (e.g. {@code /json/list}).
     * <p>
     * CDP is always a local debugging protocol, so localhost and loopback
     * addresses (127.0.0.0/8) are explicitly allowed even when URL safety
     * is enabled. The SSRF guard does NOT apply to CDP URLs the same way it
     * applies to outbound HTTP requests.
     * <p>
     * This method checks:
     * <ul>
     *   <li>The URL is not null or blank</li>
     *   <li>The scheme is {@code ws}, {@code wss}, {@code http}, or {@code https}</li>
     *   <li>The host is present and not an embedded-credential URL</li>
     *   <li>The host is not a cloud metadata endpoint</li>
     *   <li>The host is not in the configured blocked-hosts or blocked-domains lists</li>
     *   <li>Localhost and loopback addresses are always allowed (CDP is local)</li>
     * </ul>
     *
     * @param cdpUrl the CDP URL to validate
     * @return {@code null} if valid, or an error message describing why it is invalid
     */
    public String validate(String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            return "cdpUrl is empty";
        }
        String lowerCdpUrl = cdpUrl.toLowerCase();
        if (!lowerCdpUrl.startsWith("ws://")
                && !lowerCdpUrl.startsWith("wss://")
                && !lowerCdpUrl.startsWith("http://")
                && !lowerCdpUrl.startsWith("https://")) {
            return "cdpUrl must start with ws://, wss://, http://, or https:// "
                    + "(Chrome DevTools Protocol uses WebSocket or HTTP)";
        }
        URI uri;
        try {
            uri = new URI(cdpUrl);
        } catch (URISyntaxException e) {
            return "Invalid cdpUrl: " + e.getMessage();
        }
        if (uri.getHost() == null) {
            return "cdpUrl host is missing";
        }
        // Block URLs with embedded credentials
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return "cdpUrl with embedded credentials is not allowed: " + uri.getHost();
        }
        String lowerHost = uri.getHost().toLowerCase();

        // CDP is a local debugging protocol — localhost and loopback are always allowed
        if (isLocalDebugHost(lowerHost)) {
            // Still check configured blocked hosts / blocked domains even for localhost
            if (urlSafety.isHostBlocked(uri.getHost())) {
                return "cdpUrl host is blocked: " + uri.getHost();
            }
            if (properties.getWeb().getBlockedDomains().contains(uri.getHost())) {
                return "cdpUrl domain is blocked: " + uri.getHost();
            }
            return null; // localhost / loopback allowed for CDP
        }

        // Block cloud metadata endpoints (SSRF risk even for CDP)
        if (lowerHost.equals("metadata.google.internal") || lowerHost.endsWith(".metadata.google.internal")) {
            return "cdpUrl pointing to metadata endpoint is blocked";
        }

        // SSRF safety checks for non-local hosts: check blocked hosts and blocked domains
        if (urlSafety.isHostBlocked(uri.getHost())) {
            return "cdpUrl host is blocked: " + uri.getHost();
        }
        if (properties.getWeb().getBlockedDomains().contains(uri.getHost())) {
            return "cdpUrl domain is blocked: " + uri.getHost();
        }

        return null;
    }

    /**
     * Returns true if the host is a local debugging host that should always be
     * allowed for CDP URLs: {@code localhost}, {@code localhost.localdomain},
     * or a loopback IP address (127.0.0.0/8 or ::1).
     *
     * @param lowerHost the lowercased hostname or IP literal
     * @return true if the host is a local debugging host
     */
    private boolean isLocalDebugHost(String lowerHost) {
        if ("localhost".equals(lowerHost) || "localhost.localdomain".equals(lowerHost)) {
            return true;
        }
        // Loopback IPv4: 127.0.0.0/8 (any address starting with 127.)
        if (lowerHost.matches("^127(\\.\\d+){3}$")) {
            return true;
        }
        // IPv6 loopback: [::1] is represented as "::1" in URI host
        if ("::1".equals(lowerHost) || "[::1]".equals(lowerHost)) {
            return true;
        }
        // 0.0.0.0 / [::] — unspecified address used for local-only binding
        if ("0.0.0.0".equals(lowerHost) || "[::]".equals(lowerHost) || "::".equals(lowerHost)) {
            return true;
        }
        return false;
    }
}