package com.azhukov.agent.bot.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Simple in-memory cache mapping file_id → byte[] with TTL-based expiry.
 * Default TTL is 24 hours.
 */
@Service
public class MediaCache {

    private static final Logger log = LoggerFactory.getLogger(MediaCache.class);

    private record CacheEntry(byte[] data, Instant expiresAt) {}

    private final Duration ttl;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MediaCache() {
        this(Duration.ofHours(24));
    }

    public MediaCache(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * Stores data for the given fileId.
     *
     * @param fileId Telegram file_id
     * @param data   downloaded file bytes
     */
    public void put(String fileId, byte[] data) {
        if (fileId == null || data == null) return;
        cache.put(fileId, new CacheEntry(data, Instant.now().plus(ttl)));
        log.debug("Cached media for fileId={} ({} bytes)", fileId, data.length);
    }

    /**
     * Retrieves cached data if present and not expired.
     *
     * @param fileId Telegram file_id
     * @return Optional with byte[] if cached and fresh, empty otherwise
     */
    public Optional<byte[]> get(String fileId) {
        if (fileId == null) return Optional.empty();
        CacheEntry entry = cache.get(fileId);
        if (entry == null) return Optional.empty();
        if (Instant.now().isAfter(entry.expiresAt())) {
            cache.remove(fileId);
            log.debug("Expired cache entry for fileId={}", fileId);
            return Optional.empty();
        }
        return Optional.of(entry.data());
    }

    /**
     * Returns true if the cache has a non-expired entry for the fileId.
     *
     * @param fileId Telegram file_id
     * @return true if cached and fresh
     */
    public boolean contains(String fileId) {
        return get(fileId).isPresent();
    }

    /**
     * Removes a specific entry from the cache.
     *
     * @param fileId Telegram file_id
     */
    public void evict(String fileId) {
        cache.remove(fileId);
    }

    /**
     * Clears all cached entries.
     */
    public void clear() {
        cache.clear();
        log.debug("Media cache cleared");
    }

    /**
     * Removes all expired entries. Can be called periodically for cleanup.
     *
     * @return number of entries removed
     */
    public int purgeExpired() {
        int removed = 0;
        Instant now = Instant.now();
        for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
            if (now.isAfter(e.getValue().expiresAt())) {
                cache.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Purged {} expired cache entries", removed);
        }
        return removed;
    }

    /**
     * Returns the current number of entries (including possibly expired ones).
     *
     * @return cache size
     */
    public int size() {
        return cache.size();
    }
}