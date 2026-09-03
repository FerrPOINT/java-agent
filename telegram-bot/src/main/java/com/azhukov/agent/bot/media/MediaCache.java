package com.azhukov.agent.bot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Simple in-memory cache mapping file_id → byte[] with TTL-based expiry.
 * Default TTL is 24 hours.
 * <p>
 * rev-117: BOUNDED — a size cap (entries) plus a total-bytes cap with
 * insertion-order eviction. The old unbounded map kept every downloaded
 * media file (up to 20 MB each) in memory forever: TTL was lazy (entries
 * only expired when read again), so media never re-read stayed resident
 * for the process lifetime. A media-heavy chat accumulated gigabytes.
 */
@Service
@Slf4j
public class MediaCache {

    /** rev-117: max total cached bytes (default 64 MB — a few large documents). */
    static final long DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private record CacheEntry(byte[] data, Instant expiresAt) {}

    private final Duration ttl;
    private final long maxTotalBytes;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Insertion order for cheap oldest-first eviction. */
    private final java.util.concurrent.ConcurrentLinkedDeque<String> order = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicLong totalBytes = new java.util.concurrent.atomic.AtomicLong();

    public MediaCache() {
        this(Duration.ofHours(24), DEFAULT_MAX_TOTAL_BYTES);
    }

    public MediaCache(Duration ttl) {
        this(ttl, DEFAULT_MAX_TOTAL_BYTES);
    }

    public MediaCache(Duration ttl, long maxTotalBytes) {
        this.ttl = ttl;
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Stores data for the given fileId.
     *
     * @param fileId Telegram file_id
     * @param data   downloaded file bytes
     */
    public void put(String fileId, byte[] data) {
        if (fileId == null || data == null) return;
        // rev-117: never cache payloads larger than the whole budget — they
        // would evict everything else and still bust the cap.
        if (data.length > maxTotalBytes) {
            log.debug("Skipping cache for oversized media fileId={} ({} bytes > cap {})", fileId, data.length, maxTotalBytes);
            return;
        }
        CacheEntry previous = cache.put(fileId, new CacheEntry(data, Instant.now().plus(ttl)));
        if (previous != null) {
            totalBytes.addAndGet(-previous.data().length);
        } else {
            order.addLast(fileId);
        }
        long now = totalBytes.addAndGet(data.length);
        // Evict oldest entries until under the cap.
        while (now > maxTotalBytes) {
            String oldest = order.pollFirst();
            if (oldest == null) break;
            CacheEntry evicted = cache.remove(oldest);
            if (evicted != null) {
                now = totalBytes.addAndGet(-evicted.data().length);
                log.debug("Evicted cached media fileId={} ({} bytes) — over cap", oldest, evicted.data().length);
            }
        }
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
            order.remove(fileId);
            totalBytes.addAndGet(-entry.data().length);
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
        CacheEntry removed = cache.remove(fileId);
        order.remove(fileId);
        if (removed != null) {
            totalBytes.addAndGet(-removed.data().length);
        }
    }

    /**
     * Clears all cached entries.
     */
    public void clear() {
        cache.clear();
        order.clear();
        totalBytes.set(0);
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