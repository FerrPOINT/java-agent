package com.azhukov.agent.bot.client;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and normalizes fallback IP addresses for the Telegram API.
 * Rejects private, loopback, link-local, and unspecified addresses.
 * Only accepts IPv4 public addresses.
 *
 * <p>Mirrors the Python {@code _normalize_fallback_ips} in
 * {@code gateway/platforms/telegram_network.py}.
 */
@Slf4j
public final class FallbackIpValidator {

    private FallbackIpValidator() {
    }

    /**
     * Validate and normalize a list of IP address strings.
     * Removes invalid, private, loopback, link-local, and unspecified addresses.
     * Preserves order and removes duplicates.
     *
     * @param ips raw IP strings (may contain invalid entries)
     * @return validated, deduplicated list of public IPv4 addresses
     */
    public static List<String> normalize(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String raw : ips) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            String validated = validateSingle(trimmed);
            if (validated != null && seen.add(validated)) {
                result.add(validated);
            }
        }
        return result;
    }

    /**
     * Validate a single IP address string.
     *
     * @param ip raw IP string
     * @return the canonical form if valid and public IPv4, null otherwise
     */
    public static String validateSingle(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            // Only accept IPv4
            if (addr.getAddress().length != 4) {
                log.warn("Ignoring non-IPv4 Telegram fallback IP: {}", ip);
                return null;
            }
            if (addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                log.warn("Ignoring private/internal Telegram fallback IP: {}", ip);
                return null;
            }
            return addr.getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Ignoring invalid Telegram fallback IP: {}", ip);
            return null;
        }
    }

    /**
     * Parse a comma-separated string of IPs into a validated list.
     *
     * @param csv comma-separated IPs (e.g. "149.154.167.220,149.154.167.221")
     * @return validated, deduplicated list
     */
    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] parts = csv.split(",");
        return normalize(List.of(parts));
    }
}