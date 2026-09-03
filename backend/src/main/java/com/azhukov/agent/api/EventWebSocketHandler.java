package com.azhukov.agent.api;

import com.azhukov.agent.service.EventService;
import com.azhukov.agent.service.ProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
@Slf4j
public class EventWebSocketHandler extends TextWebSocketHandler {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final String SUBSCRIPTION_ATTRIBUTE = EventWebSocketHandler.class.getName() + ".subscription";
    private static final String FUTURE_ATTRIBUTE = EventWebSocketHandler.class.getName() + ".future";

    private final EventService eventService;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;
    private final Duration pollTimeout;
    private final ExecutorService executor;

    @Autowired
    public EventWebSocketHandler(EventService eventService,
                                 ProfileService profileService,
                                 ObjectMapper objectMapper) {
        this(eventService, profileService, objectMapper, DEFAULT_POLL_TIMEOUT,
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("event-ws-", 0).factory()));
    }

    EventWebSocketHandler(EventService eventService,
                          ProfileService profileService,
                          ObjectMapper objectMapper,
                          Duration pollTimeout,
                          ExecutorService executor) {
        this.eventService = eventService;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
        this.pollTimeout = pollTimeout == null ? DEFAULT_POLL_TIMEOUT : pollTimeout;
        this.executor = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Subscription subscription;
        try {
            subscription = subscription(session);
        } catch (FileNotFoundException e) {
            sendErrorAndClose(session, e.getMessage(), CloseStatus.POLICY_VIOLATION);
            return;
        } catch (IllegalArgumentException e) {
            sendErrorAndClose(session, e.getMessage(), CloseStatus.BAD_DATA);
            return;
        } catch (IOException e) {
            sendErrorAndClose(session, "Failed to read profile: " + e.getMessage(), CloseStatus.SERVER_ERROR);
            return;
        }

        session.getAttributes().put(SUBSCRIPTION_ATTRIBUTE, subscription);
        Future<?> future = executor.submit(() -> streamEvents(session, subscription));
        session.getAttributes().put(FUTURE_ATTRIBUTE, future);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object future = session.getAttributes().get(FUTURE_ATTRIBUTE);
        if (future instanceof Future<?> f) {
            f.cancel(true);
        }
    }

    private void streamEvents(WebSocketSession session, Subscription subscription) {
        long cursor = subscription.afterCursor();
        try {
            while (!Thread.currentThread().isInterrupted() && session.isOpen()) {
                List<EventService.EventEnvelope> events =
                    eventService.await(subscription.profileScope(), cursor, subscription.limit(), pollTimeout);
                for (EventService.EventEnvelope event : events) {
                    if (!session.isOpen()) {
                        return;
                    }
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
                    cursor = event.cursor();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Dashboard event websocket closed after stream failure: {}", e.getMessage());
            try {
                sendErrorAndClose(session, "Event stream failed: " + messageOf(e), CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // The connection is already gone or no longer writable.
            }
        }
    }

    private Subscription subscription(WebSocketSession session) throws IOException {
        URI uri = session.getUri();
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        Map<String, String> query = parseQuery(uri == null ? null : uri.getRawQuery());
        String profileScope = resolveProfileScope(pathProfile(path), query.get("profile"));
        long afterCursor = parseCursor(firstNonBlank(query.get("after"), query.get("cursor")));
        int limit = parseLimit(query.get("limit"));
        return new Subscription(profileScope, afterCursor, limit);
    }

    private String resolveProfileScope(String pathProfile, String queryProfile) throws IOException {
        if (hasText(pathProfile)) {
            return requireKnownProfile(pathProfile);
        }
        if (!hasText(queryProfile) || "all".equalsIgnoreCase(queryProfile.trim())) {
            return null;
        }
        return requireKnownProfile(queryProfile);
    }

    private String requireKnownProfile(String rawProfile) throws IOException {
        String profile = profileService.normalizeProfileName(rawProfile);
        profileService.validateProfileName(profile);
        profileService.requireKnownProfile(profile);
        return profile;
    }

    private static String pathProfile(String path) {
        String[] segments = path == null ? new String[0] : path.split("/");
        if (segments.length >= 4 && "p".equals(segments[1]) && "api".equals(segments[3])) {
            return segments[2];
        }
        return null;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            int equals = part.indexOf('=');
            String key = equals >= 0 ? part.substring(0, equals) : part;
            String value = equals >= 0 ? part.substring(equals + 1) : "";
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static long parseCursor(String raw) {
        if (!hasText(raw)) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0L) {
                throw new IllegalArgumentException("cursor must be greater than or equal to 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor must be an integer");
        }
    }

    private static int parseLimit(String raw) {
        if (!hasText(raw)) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1 || parsed > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer");
        }
    }

    private void sendErrorAndClose(WebSocketSession session, String error, CloseStatus status) throws Exception {
        if (!session.isOpen()) {
            return;
        }
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
            "type", "error",
            "error", error == null || error.isBlank() ? "Event stream failed" : error))));
        if (session.isOpen()) {
            session.close(status);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        if (hasText(first)) {
            return first;
        }
        return second;
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private record Subscription(String profileScope, long afterCursor, int limit) {
    }
}
