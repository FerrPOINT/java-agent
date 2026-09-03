package com.azhukov.agent.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class EventService {

    private static final int DEFAULT_MAX_EVENTS = 1_000;
    private static final int DEFAULT_REPLAY_LIMIT = 100;
    private static final int MAX_REPLAY_LIMIT = 500;
    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final Object lock = new Object();
    private final AtomicLong cursor = new AtomicLong();
    private final ArrayDeque<EventEnvelope> buffer = new ArrayDeque<>();
    private final int maxEvents;

    public EventService() {
        this(DEFAULT_MAX_EVENTS);
    }

    public EventService(int maxEvents) {
        this.maxEvents = Math.max(1, maxEvents);
    }

    public EventEnvelope publish(String type, String profile, UUID sessionId, UUID runId, Map<String, Object> payload) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        long nextCursor = cursor.incrementAndGet();
        EventEnvelope event = new EventEnvelope(
            nextCursor,
            eventId(nextCursor),
            type.trim(),
            normalizeProfile(profile),
            sessionId,
            runId,
            Instant.now(),
            immutablePayload(payload));

        synchronized (lock) {
            buffer.addLast(event);
            while (buffer.size() > maxEvents) {
                buffer.removeFirst();
            }
            lock.notifyAll();
        }
        return event;
    }

    public List<EventEnvelope> replay(String profile, Long after, int limit) {
        String scope = normalizeScope(profile);
        long afterCursor = after != null ? Math.max(0L, after) : 0L;
        int boundedLimit = boundedLimit(limit);
        synchronized (lock) {
            return replayLocked(scope, afterCursor, boundedLimit);
        }
    }

    public List<EventEnvelope> await(String profile, Long after, int limit, Duration timeout) throws InterruptedException {
        String scope = normalizeScope(profile);
        long afterCursor = after != null ? Math.max(0L, after) : 0L;
        int boundedLimit = boundedLimit(limit);
        long timeoutNanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        long deadline = System.nanoTime() + timeoutNanos;
        synchronized (lock) {
            while (true) {
                List<EventEnvelope> events = replayLocked(scope, afterCursor, boundedLimit);
                if (!events.isEmpty() || timeoutNanos == 0L) {
                    return events;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return List.of();
                }
                long millis = remaining / 1_000_000L;
                int nanos = (int) (remaining % 1_000_000L);
                lock.wait(millis, nanos);
            }
        }
    }

    private List<EventEnvelope> replayLocked(String scope, long afterCursor, int boundedLimit) {
        List<EventEnvelope> events = new ArrayList<>(boundedLimit);
        for (EventEnvelope event : buffer) {
            if (event.cursor() <= afterCursor) {
                continue;
            }
            if (scope != null && !scope.equals(event.profile())) {
                continue;
            }
            events.add(event);
            if (events.size() >= boundedLimit) {
                break;
            }
        }
        return events;
    }

    public long latestCursor() {
        return cursor.get();
    }

    public int boundedLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_REPLAY_LIMIT;
        }
        return Math.min(limit, MAX_REPLAY_LIMIT);
    }

    private String normalizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "default";
        }
        String normalized = profile.trim().toLowerCase(Locale.ROOT);
        if (!"default".equals(normalized) && !PROFILE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid profile name: " + profile);
        }
        return normalized;
    }

    private String normalizeScope(String profile) {
        if (profile == null || profile.isBlank()) {
            return null;
        }
        return normalizeProfile(profile);
    }

    private static String eventId(long cursor) {
        return "evt_" + String.format("%016d", cursor);
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public record EventEnvelope(
        long cursor,
        String id,
        String type,
        String profile,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("created_at") Instant createdAt,
        Map<String, Object> payload
    ) {
    }
}
