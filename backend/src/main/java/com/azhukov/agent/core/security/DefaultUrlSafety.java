package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
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
            "metadata.google.internal",
            "metadata.goog"
    );

    private static final List<String> PROXY_ENV_VARS = List.of(
            "HTTPS_PROXY", "https_proxy",
            "HTTP_PROXY", "http_proxy",
            "ALL_PROXY", "all_proxy"
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
        String lowerHost = normalizeHost(host);
        if (isHostBlocked(lowerHost)) {
            return false;
        }
        // Hermes parity (url_safety.py): the cloud-metadata floor applies BEFORE and
        // regardless of security.allow_private_urls — opting into private/LAN URLs
        // must never make metadata endpoints reachable.
        if (isMetadataHost(lowerHost) || isMetadataIpLiteral(lowerHost)) {
            return false;
        }
        // Loopback names are ordinary private targets under the allow-private toggle.
        if (LOCALHOST_NAMES.contains(lowerHost)) {
            return allowPrivateUrls();
        }
        // Check for encoded private IPs (SSRF bypass techniques)
        if (isEncodedPrivateIp(lowerHost)) {
            return allowPrivateUrls();
        }
        // Resolve host and check for private/loopback/link-local/metadata IPs
        if (isUnsafeAddress(lowerHost)) {
            return false;
        }
        return true;
    }

    private boolean allowPrivateUrls() {
        return properties.getSecurity().isAllowPrivateUrls();
    }

    private boolean isMetadataHost(String lowerHost) {
        return METADATA_HOSTS.contains(lowerHost)
            || lowerHost.endsWith(".metadata.google.internal")
            || lowerHost.endsWith(".metadata.goog");
    }

    /**
     * Hermes parity: literal and encoded cloud-metadata IPs (169.254.0.0/16,
     * 169.254.170.2, 169.254.169.253, 100.100.100.200, incl. decimal/octal/hex
     * encodings) are blocked before the allow-private toggle is ever consulted.
     */
    private boolean isMetadataIpLiteral(String host) {
        int[] octets = parseDottedOctets(host);
        if (octets == null) {
            return false;
        }
        if (octets[0] == 169 && octets[1] == 254) {
            return true; // link-local: AWS/GCP/Azure/DO metadata range
        }
        return octets[0] == 100 && octets[1] == 100 && octets[2] == 100 && octets[3] == 200;
    }

    /**
     * Parses dotted IPv4 forms incl. octal/hex parts (0177.0.0.1, 0x7f.0.0.1);
     * null when the host is not a dotted quad.
     */
    private int[] parseDottedOctets(String host) {
        if (!host.contains(".") || host.contains(":")) {
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
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
            return octets;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean isHostBlocked(String host) {
        List<String> blockedHosts = properties.getSecurity().getBlockedUrlHosts();
        if (blockedHosts == null || host == null) {
            return false;
        }
        String lowerHost = normalizeHost(host);
        for (String blocked : blockedHosts) {
            String b = normalizeHost(blocked);
            if (lowerHost.equals(b) || lowerHost.endsWith("." + b)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeAddress(String host) {
        try {
            // InetAddress handles DNS resolution and IP parsing.
            // It also normalizes decimal IP encodings (e.g., 2130706433 → 127.0.0.1).
            for (InetAddress address : resolveAll(host)) {
                // Cloud metadata / link-local floor: blocked regardless of allow-private.
                if (isMetadataAddress(address)) {
                    return false;
                }
                if (isUnsafeResolvedAddress(address)) {
                    return !allowPrivateUrls();
                }
            }
        } catch (UnknownHostException e) {
            if (isProxyConfigured() && !isLikelyIpLiteral(host)) {
                log.debug("Could not resolve host {}; proxy is configured, delegating DNS to proxy", host);
                return false;
            }
            log.warn("Blocked URL because DNS resolution failed for host {}: {}", host, e.getMessage());
            return true;
        }
        return false;
    }

    /**
     * Hermes parity (_ALWAYS_BLOCKED_IPS / _ALWAYS_BLOCKED_NETWORKS): cloud metadata
     * endpoints and the entire IPv4 link-local range stay blocked even when
     * security.allow-private-urls is enabled.
     */
    private boolean isMetadataAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes == null) {
            return false;
        }
        // Classify by the embedded IPv4 for IPv4-mapped IPv6 answers.
        if (bytes.length == 16 && isIpv4MappedAddress(bytes)) {
            bytes = java.util.Arrays.copyOfRange(bytes, 12, 16);
        }
        if (bytes.length != 4) {
            // fd00:ec2::254 — AWS metadata over IPv6.
            byte[] aws = {(byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};
            return java.util.Arrays.equals(bytes, aws);
        }
        int o1 = bytes[0] & 0xFF;
        int o2 = bytes[1] & 0xFF;
        int o3 = bytes[2] & 0xFF;
        int o4 = bytes[3] & 0xFF;
        // Entire 169.254.0.0/16 link-local range (AWS/GCP/Azure/DO metadata).
        if (o1 == 169 && o2 == 254) {
            return true;
        }
        // Alibaba Cloud metadata.
        return o1 == 100 && o2 == 100 && o3 == 100 && o4 == 200;
    }

    InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    boolean isProxyConfigured() {
        for (String envName : PROXY_ENV_VARS) {
            String value = System.getenv(envName);
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnsafeResolvedAddress(InetAddress address) {
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
        byte[] bytes = address.getAddress();
        if (bytes == null) {
            return false;
        }
        if (bytes.length == 4) {
            return isBlockedIpv4Range(bytes, 0);
        }
        if (bytes.length == 16) {
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique local
            }
            if (isIpv4MappedAddress(bytes)) {
                return isBlockedIpv4Range(bytes, 12);
            }
        }
        return false;
    }

    private boolean isIpv4MappedAddress(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private boolean isBlockedIpv4Range(byte[] bytes, int offset) {
        return isPrivateRange(bytes[offset] & 0xFF, bytes[offset + 1] & 0xFF);
    }

    private boolean isLikelyIpLiteral(String host) {
        return host.contains(":")
                || host.matches("(?i)^(?:0x[0-9a-f]+|[0-9][0-9a-fx.:-]*)$");
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
               (o1 == 100 && o2 >= 64 && o2 <= 127) ||
               (o1 == 172 && o2 >= 16 && o2 <= 31) ||
               (o1 == 198 && (o2 == 18 || o2 == 19)) ||
               (o1 == 192 && o2 == 168) ||
               (o1 == 169 && o2 == 254);
    }

    private String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
