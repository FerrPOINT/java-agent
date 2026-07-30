package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discovers fallback IPs for api.telegram.org via DNS-over-HTTPS (DoH).
 *
 * <p>Queries Google and Cloudflare DoH endpoints and returns all unique A records.
 * Falls back to a hardcoded seed list when DoH yields no usable IPs.
 *
 * <p>Mirrors the Python {@code discover_fallback_ips} in
 * {@code gateway/platforms/telegram_network.py}.
 */
@Slf4j
public class DohIpDiscovery {

    public static final String TELEGRAM_API_HOST = "api.telegram.org";

    private static final List<DohProvider> DOH_PROVIDERS = List.of(
        new DohProvider("https://dns.google/resolve", TELEGRAM_API_HOST, null),
        new DohProvider("https://cloudflare-dns.com/dns-query", TELEGRAM_API_HOST, "application/dns-json")
    );

    /** Last-resort IPs when DoH is also blocked — stable Telegram Bot API endpoints. */
    public static final List<String> SEED_FALLBACK_IPS = List.of("149.154.167.220");

    private static final Duration DOH_TIMEOUT = Duration.ofSeconds(4);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DohIpDiscovery() {
        this(new ObjectMapper(), createDefaultHttpClient());
    }

    public DohIpDiscovery(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    private static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(DOH_TIMEOUT)
            .build();
    }

    /**
     * Discover fallback IPs via DoH.
     *
     * <p>Queries all providers and returns validated, deduplicated IPs.
     * Falls back to seed IPs when DoH yields no usable results.
     *
     * @return validated list of fallback IPs (never empty)
     */
    public List<String> discover() {
        Set<String> discovered = new LinkedHashSet<>();
        for (DohProvider provider : DOH_PROVIDERS) {
            try {
                List<String> ips = queryProvider(provider);
                discovered.addAll(ips);
            } catch (Exception e) {
                log.debug("DoH query to {} failed: {}", provider.url(), e.getMessage());
            }
        }

        List<String> validated = FallbackIpValidator.normalize(new ArrayList<>(discovered));
        if (!validated.isEmpty()) {
            log.debug("Discovered Telegram fallback IPs via DoH: {}", String.join(", ", validated));
            return validated;
        }

        log.info("DoH discovery yielded no usable IPs; using seed fallback IPs: {}",
            String.join(", ", SEED_FALLBACK_IPS));
        return FallbackIpValidator.normalize(SEED_FALLBACK_IPS);
    }

    /**
     * Query a single DoH provider and return A-record IPs.
     *
     * @param provider DoH provider configuration
     * @return list of IP strings from A records
     */
    List<String> queryProvider(DohProvider provider) throws Exception {
        String url = provider.url() + "?name=" + provider.name() + "&type=A";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(DOH_TIMEOUT)
            .GET();

        if (provider.acceptHeader() != null) {
            requestBuilder.header("Accept", provider.acceptHeader());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.debug("DoH query to {} returned HTTP {}", provider.url(), response.statusCode());
            return List.of();
        }

        return parseDohResponse(response.body());
    }

    /**
     * Parse the DoH JSON response and extract A-record IPs.
     *
     * @param body JSON response body
     * @return list of IP strings
     */
    List<String> parseDohResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode answers = root.get("Answer");
        if (answers == null || !answers.isArray()) {
            return List.of();
        }
        List<String> ips = new ArrayList<>();
        for (JsonNode answer : answers) {
            JsonNode typeNode = answer.get("type");
            if (typeNode == null || typeNode.asInt() != 1) {
                continue; // Not an A record
            }
            JsonNode dataNode = answer.get("data");
            if (dataNode != null) {
                String ip = dataNode.asText().trim();
                if (!ip.isEmpty()) {
                    ips.add(ip);
                }
            }
        }
        return ips;
    }

    /** DoH provider configuration. */
    record DohProvider(String url, String name, String acceptHeader) {
    }
}