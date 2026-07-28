package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks cached prompt prefixes per session to enable prompt caching optimization.
 * Uses SHA-256 to hash system prompt prefixes and tracks cache validity.
 */
@Component
@Slf4j
public class PromptCacheTracker {

    private final AgentProperties properties;

    private final Map<String, String> sessionPrefixHashes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheHits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheMisses = new ConcurrentHashMap<>();

    public PromptCacheTracker(AgentProperties properties) {
        this.properties = properties;
    }

    public void markCached(String sessionId, String prefixHash) {
        if (!properties.getPromptCaching().isEnabled()) return;
        sessionPrefixHashes.put(sessionId, prefixHash);
        log.debug("Marked cached prefix for session {}: {}", sessionId, prefixHash);
    }

    public boolean isCacheValid(String sessionId, String prefixHash) {
        if (!properties.getPromptCaching().isEnabled()) return false;
        String cached = sessionPrefixHashes.get(sessionId);
        if (cached == null) {
            trackMiss(sessionId);
            return false;
        }
        boolean valid = cached.equals(prefixHash);
        if (valid) {
            trackHit(sessionId);
        } else {
            trackMiss(sessionId);
        }
        return valid;
    }

    public void invalidate(String sessionId) {
        sessionPrefixHashes.remove(sessionId);
        log.debug("Invalidated cache for session {}", sessionId);
    }

    public Map<String, Object> getCacheStats() {
        long totalHits = cacheHits.values().stream().mapToLong(AtomicLong::get).sum();
        long totalMisses = cacheMisses.values().stream().mapToLong(AtomicLong::get).sum();
        long total = totalHits + totalMisses;
        double hitRate = total > 0 ? (double) totalHits / total * 100 : 0;
        return Map.of(
            "cachedSessions", sessionPrefixHashes.size(),
            "totalHits", totalHits,
            "totalMisses", totalMisses,
            "hitRate", String.format("%.2f%%", hitRate)
        );
    }

    public static String hashPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(prefix.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 32); // Use first 32 chars
        } catch (Exception e) {
            return Integer.toHexString(prefix.hashCode());
        }
    }

    private void trackHit(String sessionId) {
        cacheHits.computeIfAbsent(sessionId, k -> new AtomicLong(0)).incrementAndGet();
    }

    private void trackMiss(String sessionId) {
        cacheMisses.computeIfAbsent(sessionId, k -> new AtomicLong(0)).incrementAndGet();
    }
}