package com.azhukov.agent.bot.polling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * B3.8: Resolves fallback IP addresses for api.telegram.org when DNS resolution fails.
 * <p>
 * Parses the {@code TELEGRAM_FALLBACK_IPS} environment variable (comma-separated IPs).
 * When DNS resolution for api.telegram.org fails, tries the fallback IPs in random order.
 */
@Component
public class TelegramFallbackIpResolver {

    private static final Logger log = LoggerFactory.getLogger(TelegramFallbackIpResolver.class);

    private static final String HOST = "api.telegram.org";
    private static final String ENV_VAR = "TELEGRAM_FALLBACK_IPS";

    private final List<String> fallbackIps;

    public TelegramFallbackIpResolver() {
        this.fallbackIps = parseFallbackIps(System.getenv(ENV_VAR));
        if (!fallbackIps.isEmpty()) {
            log.info("Loaded {} fallback IPs for {}", fallbackIps.size(), HOST);
        }
    }

    /**
     * Resolve the IP address for api.telegram.org, with fallback support.
     * First tries standard DNS resolution. If that fails, tries a random fallback IP.
     *
     * @return the resolved IP address, or empty if all resolution attempts fail
     */
    public Optional<String> resolve() {
        // Try standard DNS first
        try {
            InetAddress addr = InetAddress.getByName(HOST);
            log.debug("DNS resolved {} to {}", HOST, addr.getHostAddress());
            return Optional.of(addr.getHostAddress());
        } catch (UnknownHostException e) {
            log.warn("DNS resolution failed for {}: {}", HOST, e.getMessage());
        }

        // Try fallback IPs
        if (fallbackIps.isEmpty()) {
            log.error("No fallback IPs configured for {}", HOST);
            return Optional.empty();
        }

        // Pick a random fallback IP
        String ip = fallbackIps.get(ThreadLocalRandom.current().nextInt(fallbackIps.size()));
        log.info("Using fallback IP {} for {}", ip, HOST);
        return Optional.of(ip);
    }

    /**
     * Get the list of configured fallback IPs.
     */
    public List<String> getFallbackIps() {
        return List.copyOf(fallbackIps);
    }

    /**
     * Check if any fallback IPs are configured.
     */
    public boolean hasFallbackIps() {
        return !fallbackIps.isEmpty();
    }

    /**
     * Parse the TELEGRAM_FALLBACK_IPS environment variable into a list of IPs.
     */
    static List<String> parseFallbackIps(String envValue) {
        List<String> ips = new ArrayList<>();
        if (envValue == null || envValue.isBlank()) {
            return ips;
        }
        for (String part : envValue.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ips.add(trimmed);
            }
        }
        return ips;
    }
}