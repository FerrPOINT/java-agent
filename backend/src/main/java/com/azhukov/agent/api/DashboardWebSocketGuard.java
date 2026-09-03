package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DashboardWebSocketGuard {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Set<String> WILDCARD_BINDS = Set.of("0.0.0.0", "::");
    private static final String DEFAULT_BOUND_HOST = "127.0.0.1";

    private final AgentProperties properties;
    private final String boundHost;

    public DashboardWebSocketGuard(AgentProperties properties,
                                   @Value("${server.address:}") String boundHost) {
        this.properties = properties;
        this.boundHost = normalizeBoundHost(boundHost);
    }

    String rejectionReason(String hostHeader, String originHeader) {
        Set<String> trustedPublicHosts = trustedPublicHosts();
        if (!isAcceptedHost(hostHeader, boundHost, trustedPublicHosts)) {
            return "host_mismatch";
        }
        if (originHeader == null || originHeader.isBlank()) {
            return null;
        }

        URI origin;
        try {
            origin = new URI(originHeader.trim());
        } catch (URISyntaxException e) {
            return "origin_mismatch";
        }
        String scheme = origin.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }
        String authority = origin.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            return "origin_mismatch";
        }
        return isAcceptedHost(authority, boundHost, trustedPublicHosts) ? null : "origin_mismatch";
    }

    boolean isAcceptedHost(String hostHeader, String boundHost, Set<String> trustedPublicHosts) {
        String hostOnly = hostHeaderHostname(hostHeader);
        if (hostOnly.isBlank()) {
            return false;
        }
        if (trustedPublicHosts.contains(hostOnly)) {
            return true;
        }
        String normalizedBound = normalizeBoundHost(boundHost);
        if (WILDCARD_BINDS.contains(normalizedBound)) {
            return true;
        }
        if (LOOPBACK_HOSTS.contains(normalizedBound)) {
            return LOOPBACK_HOSTS.contains(hostOnly);
        }
        return hostOnly.equals(normalizedBound);
    }

    String hostHeaderHostname(String hostHeader) {
        String value = hostHeader == null ? "" : hostHeader.trim();
        if (value.isBlank()) {
            return "";
        }
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\'' || c == '<' || c == '>' || Character.isWhitespace(c)) {
                return "";
            }
        }
        if (value.contains("://") || value.contains("/") || value.contains("?")
            || value.contains("#") || value.contains("@")) {
            return "";
        }

        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return "";
            }
            String hostname = value.substring(1, close);
            if (!hostname.contains(":")) {
                return "";
            }
            String suffix = value.substring(close + 1);
            if (!suffix.isBlank() && !suffix.matches(":\\d+")) {
                return "";
            }
            return hostname.toLowerCase(Locale.ROOT);
        }

        if (value.chars().filter(ch -> ch == ':').count() > 1) {
            return "";
        }
        if (value.contains(":")) {
            int colon = value.lastIndexOf(':');
            String hostname = value.substring(0, colon);
            String port = value.substring(colon + 1);
            if (hostname.isBlank() || port.isBlank() || !port.chars().allMatch(Character::isDigit)) {
                return "";
            }
            return hostname.toLowerCase(Locale.ROOT);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private Set<String> trustedPublicHosts() {
        AgentProperties.ApiProperties api = properties.getApi();
        List<String> origins = api == null || api.getCorsOrigins() == null ? List.of() : api.getCorsOrigins();
        return origins.stream()
            .filter(origin -> origin != null && !origin.isBlank() && !"*".equals(origin.trim()))
            .map(this::trustedHostFromOrigin)
            .filter(host -> host != null && !host.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private String trustedHostFromOrigin(String rawOrigin) {
        String value = rawOrigin.trim();
        try {
            URI uri = new URI(value);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost().toLowerCase(Locale.ROOT);
            }
        } catch (URISyntaxException ignored) {
            // Fall through to authority-style parsing for operator-provided host aliases.
        }
        return hostHeaderHostname(value);
    }

    private static String normalizeBoundHost(String rawBoundHost) {
        String value = rawBoundHost == null || rawBoundHost.isBlank() ? DEFAULT_BOUND_HOST : rawBoundHost.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
