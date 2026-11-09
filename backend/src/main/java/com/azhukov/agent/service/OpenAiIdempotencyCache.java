package com.azhukov.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class OpenAiIdempotencyCache {

    private static final int MAX_ITEMS = 1_000;
    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<RequestKey, CachedEntry> store = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<InFlightKey, CompletableFuture<Result>> inflight = new HashMap<>();

    public record Scope(String principal, String origin, String role, String owner, String route) {}

    public record Result(ResponseEntity<?> response, UUID sessionId) {}

    public String fingerprint(ObjectMapper objectMapper, Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

    public Result getOrCompute(Scope scope, String idempotencyKey, Supplier<String> fingerprintSupplier,
                               Supplier<Result> supplier, Consumer<Result> verifyReplay) {
        if (scope == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return supplier.get();
        }
        String fingerprint = fingerprintSupplier.get();
        RequestKey key = new RequestKey(scope, idempotencyKey.trim());
        InFlightKey runningKey = new InFlightKey(key, fingerprint);
        CompletableFuture<Result> created = new CompletableFuture<>();
        CompletableFuture<Result> running = null;
        Result hit = null;
        synchronized (store) {
            purgeExpiredLocked();
            CachedEntry cached = store.get(key);
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                hit = cached.value();
            } else {
                running = inflight.putIfAbsent(runningKey, created);
            }
        }
        if (hit != null) {
            verifyReplay.accept(hit);
            return hit;
        }
        if (running != null) {
            Result result = await(running);
            verifyReplay.accept(result);
            return result;
        }
        try {
            Result result = supplier.get();
            synchronized (store) {
                if (result.response().getStatusCode().is2xxSuccessful()) {
                    store.put(key, new CachedEntry(fingerprint, result, Instant.now()));
                    evictOldestLocked();
                }
            }
            created.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (store) {
                inflight.remove(runningKey);
            }
        }
    }

    private static Result await(CompletableFuture<Result> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
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
        Iterator<RequestKey> keys = store.keySet().iterator();
        while (store.size() > MAX_ITEMS && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }

    private record CachedEntry(String fingerprint, Result value, Instant createdAt) {}

    private record RequestKey(Scope scope, String key) {}

    private record InFlightKey(RequestKey key, String fingerprint) {}
}
