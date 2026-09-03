package com.azhukov.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
public class OpenAiIdempotencyCache {

    private static final int MAX_ITEMS = 1_000;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, CachedEntry> store = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentMap<CacheKey, CompletableFuture<CachedValue>> inflight = new ConcurrentHashMap<>();

    public String fingerprint(String scope, Object request, ObjectMapper objectMapper) {
        return fingerprint(scope, objectMapper, request);
    }

    public String fingerprint(String scope, ObjectMapper objectMapper, Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((scope != null ? scope : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (parts != null) {
                for (Object part : parts) {
                    digest.update((byte) 0);
                    digest.update(objectMapper.writeValueAsBytes(part));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to fingerprint idempotent OpenAI request", e);
        }
    }

    public <T> T getOrCompute(String idempotencyKey, Supplier<String> fingerprintSupplier, Supplier<T> supplier) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return supplier.get();
        }
        return getOrCompute(idempotencyKey, fingerprintSupplier.get(), supplier);
    }

    public <T> T getOrCompute(String idempotencyKey, String fingerprint, Supplier<T> supplier) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return supplier.get();
        }
        String key = idempotencyKey.trim();
        CachedEntry cached;
        synchronized (store) {
            purgeExpiredLocked();
            cached = store.get(key);
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.value().get();
            }
        }

        CacheKey inflightKey = new CacheKey(key, fingerprint);
        CompletableFuture<CachedValue> created = new CompletableFuture<>();
        CompletableFuture<CachedValue> running = inflight.putIfAbsent(inflightKey, created);
        if (running == null) {
            try {
                T result = supplier.get();
                CachedValue value = new CachedValue(result);
                synchronized (store) {
                    store.put(key, new CachedEntry(fingerprint, value, Instant.now()));
                    purgeExpiredLocked();
                    evictOldestLocked();
                }
                created.complete(value);
                return result;
            } catch (RuntimeException | Error e) {
                created.completeExceptionally(e);
                throw e;
            } finally {
                inflight.remove(inflightKey, created);
            }
        }

        try {
            return running.join().get();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private void purgeExpiredLocked() {
        Instant cutoff = Instant.now().minus(TTL);
        Iterator<CachedEntry> entries = store.values().iterator();
        while (entries.hasNext()) {
            if (entries.next().createdAt().isBefore(cutoff)) {
                entries.remove();
            }
        }
    }

    private void evictOldestLocked() {
        Iterator<String> keys = store.keySet().iterator();
        while (store.size() > MAX_ITEMS && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private record CachedValue(Object value) {
        <T> T get() {
            return (T) value;
        }
    }

    private record CachedEntry(String fingerprint, CachedValue value, Instant createdAt) {}

    private record CacheKey(String key, String fingerprint) {}
}
