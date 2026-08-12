package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultUrlSafety implements UrlSafety {

    private final AgentProperties properties;

    private static final Set<String> LOCALHOST_NAMES = Set.of(
            "localhost", "localhost.localdomain"
    );

    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata.google.internal"
    );

    @Override
    public boolean isUrlAllowed(String url) {
        if (!properties.getSecurity().isUrlSafetyEnabled()) {
            return true;
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        // Block URLs with embedded credentials
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return false;
        }
        // Check configured blocked hosts
        if (isHostBlocked(host)) {
            return false;
        }
        // Block localhost and known metadata endpoints by name
        String lowerHost = host.toLowerCase();
        if (LOCALHOST_NAMES.contains(lowerHost)) {
            return false;
        }
        if (METADATA_HOSTS.contains(lowerHost) || lowerHost.endsWith(".metadata.google.internal")) {
            return false;
        }
        // Check for encoded private IPs (SSRF bypass techniques)
        if (isEncodedPrivateIp(host)) {
            return false;
        }
        // Resolve host and check for private/loopback/link-local/metadata IPs
        if (isUnsafeAddress(host)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isHostBlocked(String host) {
        List<String> blockedHosts = properties.getSecurity().getBlockedUrlHosts();
        if (blockedHosts == null || host == null) {
            return false;
        }
        String lowerHost = host.toLowerCase();
        for (String blocked : blockedHosts) {
            String b = blocked.toLowerCase();
            if (lowerHost.equals(b) || lowerHost.endsWith("." + b)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeAddress(String host) {
        try {
            // InetAddress.getByName handles DNS resolution and IP parsing.
            // It also normalizes decimal IP encodings (e.g., 2130706433 → 127.0.0.1).
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress()) {
                return true;
            }
            if (address.isAnyLocalAddress()) {
                return true; // 0.0.0.0 or ::
            }
            if (address.isSiteLocalAddress()) {
                return true; // 10.x, 172.16-31.x, 192.168.x
            }
            if (address.isLinkLocalAddress()) {
                return true; // 169.254.x.x, fe80::
            }
            if (address.isMulticastAddress()) {
                return true;
            }
            // Check for IPv6 unique local addresses (fc00::/7)
            // Java's isSiteLocalAddress() does not cover IPv6 ULA
            byte[] bytes = address.getAddress();
            if (bytes != null && bytes.length == 16) {
                if ((bytes[0] & 0xFE) == 0xFC) {
                    return true; // fc00::/7 unique local
                }
            }
        } catch (UnknownHostException e) {
            // Can't resolve — allow, downstream HTTP client will handle
            log.debug("Could not resolve host {}: {}", host, e.getMessage());
        }
        return false;
    }

    /**
     * Detects private/loopback IPs encoded in non-standard formats that
     * InetAddress.getByName might not normalize (octal, hex dotted notation).
     */
    private boolean isEncodedPrivateIp(String host) {
        // Pure hex IP (e.g., 0x7f000001 = 127.0.0.1)
        if (host.toLowerCase().startsWith("0x") && !host.contains(".") && !host.contains(":")) {
            try {
                long ip = Long.parseLong(host.substring(2), 16);
                if (ip >= 0 && ip <= 0xFFFFFFFFL) {
                    return isPrivateRange((int) ((ip >> 24) & 0xFF), (int) ((ip >> 16) & 0xFF));
                }
            } catch (NumberFormatException e) { log.trace("IP parsing failed for '{}': {}", host, e.getMessage()); }
        }
        // Dotted notation with octal/hex parts (e.g., 0177.0.0.1 = 127.0.0.1)
        if (host.contains(".") && !host.contains(":")) {
            String[] parts = host.split("\\.");
            if (parts.length == 4) {
                try {
                    int[] octets = new int[4];
                    for (int i = 0; i < 4; i++) {
                        String p = parts[i];
                        if (p.toLowerCase().startsWith("0x")) {
                            octets[i] = Integer.parseInt(p.substring(2), 16);
                        } else if (p.startsWith("0") && p.length() > 1 && p.matches("[0-7]+")) {
                            octets[i] = Integer.parseInt(p, 8);
                        } else {
                            octets[i] = Integer.parseInt(p);
                        }
                    }
                    return isPrivateRange(octets[0], octets[1]);
                } catch (NumberFormatException e) { log.trace("IP parsing failed for '{}': {}", host, e.getMessage()); }
            }
        }
        return false;
    }

    private boolean isPrivateRange(int o1, int o2) {
        return o1 == 0 || o1 == 127 ||
               o1 == 10 ||
               (o1 == 172 && o2 >= 16 && o2 <= 31) ||
               (o1 == 192 && o2 == 168) ||
               (o1 == 169 && o2 == 254);
    }
}