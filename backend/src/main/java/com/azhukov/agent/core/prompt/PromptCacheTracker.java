package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks cached prompt prefixes per session to enable prompt caching optimization.
 * Uses SHA-256 to hash system prompt prefixes and tracks cache validity.
 * <p>
 * Also provides Anthropic-style cache_control breakpoint injection for
 * Anthropic-compatible providers (4 breakpoints: system prompt, tool definitions,
 * early messages, latest user message).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PromptCacheTracker {

    private final AgentProperties properties;

    private final Map<String, String> sessionPrefixHashes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheHits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> cacheMisses = new ConcurrentHashMap<>();

    /** Cached system prompt per session — built once, only rebuilt on compression */
    private final Map<String, CachedSystemPrompt> cachedSystemPrompts = new ConcurrentHashMap<>();

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
        cachedSystemPrompts.remove(sessionId);
        log.debug("Invalidated cache for session {}", sessionId);
    }

    /**
     * Get or build the cached system prompt for a session.
     * The prompt is built once and reused across all turns — only rebuilt
     * after invalidation (e.g. context compression events).
     */
    public CachedSystemPrompt getOrBuild(String sessionId, java.util.function.Supplier<CachedSystemPrompt> builder) {
        CachedSystemPrompt cached = cachedSystemPrompts.get(sessionId);
        if (cached != null) {
            return cached;
        }
        cached = builder.get();
        cachedSystemPrompts.put(sessionId, cached);
        return cached;
    }

    /**
     * Invalidate the cached system prompt, forcing rebuild on next turn.
     * Called after context compression events.
     */
    public void invalidateSystemPrompt(String sessionId) {
        cachedSystemPrompts.remove(sessionId);
        sessionPrefixHashes.remove(sessionId);
        log.debug("Invalidated system prompt cache for session {}", sessionId);
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
            return sb.toString().substring(0, 32);
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

    /**
     * Cached system prompt holder with three-tier composition.
     */
    public record CachedSystemPrompt(
        String stable,     // identity + rules (never changes per session)
        String contextTier, // session info, skills index (changes on session events only)
        String volatileTier, // memory, dynamic context (changes per turn)
        String fullPrompt
    ) {
        /**
         * Build the full system prompt from all tiers, joined with proper separators.
         * Memory is NOT included here — it's moved to user message prefix to preserve cache.
         */
        public static CachedSystemPrompt of(String stable, String contextTier, String volatileTier) {
            StringBuilder sb = new StringBuilder();
            if (stable != null && !stable.isBlank()) {
                sb.append(stable);
            }
            if (contextTier != null && !contextTier.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(contextTier);
            }
            if (volatileTier != null && !volatileTier.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(volatileTier);
            }
            return new CachedSystemPrompt(stable, contextTier, volatileTier, sb.toString());
        }
    }

    /**
     * Inject cache_control breakpoints into API messages for Anthropic-compatible providers.
     * <p>
     * 4 breakpoints: after system prompt, after tool definitions, after early messages,
     * before latest user message.
     *
     * @param apiMessages the messages to inject breakpoints into (will be deep-copied)
     * @param cacheTtl    TTL for cache markers ("5m" or "1h")
     * @return new list of messages with cache_control breakpoints injected
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> applyAnthropicCacheControl(
        List<Map<String, Object>> apiMessages,
        String cacheTtl
    ) {
        if (apiMessages == null || apiMessages.isEmpty()) {
            return apiMessages;
        }

        // Deep copy
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Map<String, Object> msg : apiMessages) {
            messages.add(new LinkedHashMap<>(msg));
        }

        Map<String, Object> marker = buildCacheMarker(cacheTtl);
        int breakpointsUsed = 0;

        // Breakpoint 1: after system/developer prompt
        if (!messages.isEmpty() && ("system".equals(messages.get(0).get("role"))
                || "developer".equals(messages.get(0).get("role")))) {
            applyCacheMarker(messages.get(0), marker);
            breakpointsUsed++;
        }

        // Breakpoint 2: after tool definitions (if present as a system/tool message)
        // In OpenAI format, tools are separate from messages. If a tool message exists,
        // apply cache marker to it.
        for (Map<String, Object> msg : messages) {
            if (breakpointsUsed >= 4) break;
            Object role = msg.get("role");
            if ("tool".equals(role) && breakpointsUsed < 2) {
                applyCacheMarker(msg, marker);
                breakpointsUsed++;
                break;
            }
        }

        // Remaining breakpoints: last non-system messages (up to 4 - breakpointsUsed)
        int remaining = 4 - breakpointsUsed;
        List<Integer> nonSysIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String role = (String) messages.get(i).get("role");
            if (!"system".equals(role) && !"developer".equals(role)) {
                nonSysIndices.add(i);
            }
        }
        // Apply to last N non-system messages
        for (int i = nonSysIndices.size() - 1; i >= 0 && remaining > 0; i--, remaining--) {
            applyCacheMarker(messages.get(nonSysIndices.get(i)), marker);
        }

        return messages;
    }

    private void applyCacheMarker(Map<String, Object> msg, Map<String, Object> marker) {
        Object content = msg.get("content");
        if (content == null || "".equals(content)) {
            msg.put("cache_control", marker);
            return;
        }
        if (content instanceof String strContent) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", strContent);
            textPart.put("cache_control", marker);
            msg.put("content", List.of(textPart));
        }
        // If content is already a list, add cache_control to the last element
        if (content instanceof List<?> list && !list.isEmpty()) {
            Object last = list.get(list.size() - 1);
            if (last instanceof Map<?, ?> lastMap) {
                ((Map<String, Object>) lastMap).put("cache_control", marker);
            }
        }
    }

    private Map<String, Object> buildCacheMarker(String ttl) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", "ephemeral");
        if ("1h".equals(ttl)) {
            marker.put("ttl", "1h");
        }
        return marker;
    }
}