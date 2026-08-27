package com.azhukov.agent.client.langchain4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P-06 (Hermes commit 21b92d2687): an empty-response retry against an
 * OpenRouter endpoint must bypass OpenRouter's response cache, otherwise the
 * retry can replay the cached EMPTY answer and consume the whole empty-retry
 * budget without ever reaching the provider.
 *
 * <p>The runtime sets {@link #markEmptyRetry()} after the first empty
 * completion; the per-request header supplier consults the flag (ThreadLocal:
 * one logical call per thread) and merges {@code X-OpenRouter-Cache: false}
 * into the configured static headers. Non-OpenRouter endpoints and
 * non-retry calls are untouched.</p>
 */
public final class EmptyRetryCacheBypass {

    public static final String CACHE_HEADER = "X-OpenRouter-Cache";

    private static final ThreadLocal<Boolean> EMPTY_RETRY = new ThreadLocal<>();

    private EmptyRetryCacheBypass() {
    }

    /** Called by the runtime right before an empty-response retry model call. */
    public static void markEmptyRetry() {
        EMPTY_RETRY.set(Boolean.TRUE);
    }

    /** Clears the flag after the retried call finished (always in finally). */
    public static void clear() {
        EMPTY_RETRY.remove();
    }

    static boolean isEmptyRetry() {
        return Boolean.TRUE.equals(EMPTY_RETRY.get());
    }

    static boolean isOpenRouterUrl(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        String lower = baseUrl.toLowerCase();
        // host must be openrouter.ai or *.openrouter.ai — NOT merely contain
        // the substring (myopenrouter.example.com is a different provider)
        try {
            String host = java.net.URI.create(lower.trim()).getHost();
            if (host != null) {
                return host.equals("openrouter.ai") || host.endsWith(".openrouter.ai");
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to substring heuristic for odd but parseable prefixes
        }
        return lower.contains("://openrouter.ai") || lower.contains("://api.openrouter.ai")
            || lower.startsWith("openrouter.ai");
    }

    /**
     * Effective request headers: configured static headers plus the cache
     * bypass when this call is an OpenRouter empty-response retry.
     */
    public static Map<String, String> headersFor(String baseUrl, Map<String, String> configuredHeaders) {
        boolean bypass = isEmptyRetry() && isOpenRouterUrl(baseUrl);
        if (configuredHeaders == null || configuredHeaders.isEmpty()) {
            return bypass ? Map.of(CACHE_HEADER, "false") : Map.of();
        }
        Map<String, String> merged = new LinkedHashMap<>(configuredHeaders);
        if (bypass) {
            merged.put(CACHE_HEADER, "false");
        }
        return merged;
    }
}
