package com.azhukov.agent.bot.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MediaCacheTest {

    private MediaCache cache;

    @BeforeEach
    void setUp() {
        cache = new MediaCache(Duration.ofHours(24));
    }

    @Test
    void put_andGet_returnsData() {
        byte[] data = "test-data".getBytes();
        cache.put("file-1", data);

        Optional<byte[]> result = cache.get("file-1");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(data);
    }

    @Test
    void get_returnsEmptyForMissingKey() {
        Optional<byte[]> result = cache.get("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void get_returnsEmptyForNullKey() {
        Optional<byte[]> result = cache.get(null);

        assertThat(result).isEmpty();
    }

    @Test
    void put_nullFileId_doesNothing() {
        cache.put(null, "data".getBytes());

        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void put_nullData_doesNothing() {
        cache.put("file-1", null);

        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void contains_trueForCachedEntry() {
        cache.put("file-1", "data".getBytes());

        assertThat(cache.contains("file-1")).isTrue();
    }

    @Test
    void contains_falseForMissingEntry() {
        assertThat(cache.contains("file-1")).isFalse();
    }

    @Test
    void evict_removesEntry() {
        cache.put("file-1", "data".getBytes());
        cache.evict("file-1");

        assertThat(cache.contains("file-1")).isFalse();
        assertThat(cache.get("file-1")).isEmpty();
    }

    @Test
    void clear_removesAllEntries() {
        cache.put("file-1", "data1".getBytes());
        cache.put("file-2", "data2".getBytes());
        cache.clear();

        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void size_returnsCount() {
        cache.put("file-1", "data1".getBytes());
        cache.put("file-2", "data2".getBytes());

        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void get_returnsEmptyAfterTtlExpiry() {
        // Use a very short TTL
        MediaCache shortCache = new MediaCache(Duration.ofMillis(50));
        shortCache.put("file-1", "data".getBytes());

        // Immediately available
        assertThat(shortCache.get("file-1")).isPresent();

        // Wait for expiry
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(shortCache.get("file-1")).isEmpty();
    }

    @Test
    void purgeExpired_removesExpiredEntries() {
        MediaCache shortCache = new MediaCache(Duration.ofMillis(50));
        shortCache.put("file-1", "data1".getBytes());
        shortCache.put("file-2", "data2".getBytes());

        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int removed = shortCache.purgeExpired();

        assertThat(removed).isEqualTo(2);
        assertThat(shortCache.size()).isEqualTo(0);
    }

    @Test
    void purgeExpired_keepsValidEntries() {
        cache.put("file-1", "data1".getBytes());
        cache.put("file-2", "data2".getBytes());

        int removed = cache.purgeExpired();

        assertThat(removed).isEqualTo(0);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void put_overwritesExistingEntry() {
        cache.put("file-1", "old".getBytes());
        cache.put("file-1", "new".getBytes());

        Optional<byte[]> result = cache.get("file-1");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("new".getBytes());
    }
}