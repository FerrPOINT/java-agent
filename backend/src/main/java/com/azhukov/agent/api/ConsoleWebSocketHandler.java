package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.ConsoleTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams owner-authorized durable console output with reconnect cursors.
 *
 * <p>Persistent task/output ledgers, rather than the transient process map,
 * are the source of truth for replay after a disconnect or application restart.
 */
@Component
@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_LINES_PER_FRAME = 500;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(2);

    private final ConsoleTaskService taskService;
    private final ObjectMapper objectMapper;
    private final Duration pollInterval;
    private final ExecutorService executor;

    @Autowired
    public ConsoleWebSocketHandler(ConsoleTaskService taskService, ObjectMapper objectMapper) {
        this(taskService, objectMapper, DEFAULT_POLL_INTERVAL,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("console-ws-", 0).factory()));
    }

    ConsoleWebSocketHandler(ConsoleTaskService taskService,
                            ObjectMapper objectMapper,
                            Duration pollInterval,
                            ExecutorService executor) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.pollInterval = clamp(pollInterval);
        this.executor = executor;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = taskId(session);
        if (taskId == null || taskId.isBlank()) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String profile = pathProfile(session);
        String userId = userId(session);
        if (taskService.status(profile, userId, taskId).isEmpty()) {
            send(session, errorFrame("unknown_command_task", taskId));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        executor.execute(() -> stream(session, profile, userId, taskId));
    }

    private void stream(WebSocketSession session, String profile, String userId, String taskId) {
        long cursor = cursor(session);
        try {
            while (session.isOpen()) {
                var output = taskService.output(profile, userId, taskId, cursor, MAX_LINES_PER_FRAME);
                if (output.isEmpty()) {
                    send(session, errorFrame("unknown_command_task", taskId));
                    closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                Map<String, Object> payload = output.get();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> lines = (List<Map<String, Object>>) payload.get("lines");
                if (!lines.isEmpty()) {
                    cursor = ((Number) payload.get("cursor")).longValue();
                    send(session, frame("output", taskId, lines, cursor));
                }
                var task = taskService.status(profile, userId, taskId);
                if (task.isEmpty()) {
                    send(session, errorFrame("unknown_command_task", taskId));
                    closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                if (!"running".equals(task.get().getState())) {
                    send(session, frame("exit", taskId, List.of(), cursor));
                    session.close(CloseStatus.NORMAL);
                    return;
                }
                Thread.sleep(pollInterval.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(session, CloseStatus.GOING_AWAY);
        } catch (Exception e) {
            log.warn("console ws stream failed for task {}: {}", taskId, String.valueOf(e));
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws Exception {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        }
    }

    private Map<String, Object> errorFrame(String code, String taskId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "error");
        frame.put("code", code);
        frame.put("id", taskId);
        return frame;
    }

    private Map<String, Object> frame(String type, String taskId, List<Map<String, Object>> lines, long cursor) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("id", taskId);
        frame.put("cursor", cursor);
        if (!lines.isEmpty()) {
            frame.put("lines", lines);
        }
        return frame;
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // session already closed
        }
    }

    private static String taskId(WebSocketSession session) {
        return query(session).get("id");
    }

    private static long cursor(WebSocketSession session) {
        String raw = query(session).getOrDefault("after", query(session).getOrDefault("cursor", "0"));
        try {
            return Math.max(0, Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> query(WebSocketSession session) {
        URI uri = session.getUri();
        Map<String, String> values = new LinkedHashMap<>();
        if (uri == null || uri.getRawQuery() == null) {
            return values;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static String pathProfile(WebSocketSession session) {
        URI uri = session.getUri();
        String[] segments = uri == null || uri.getPath() == null ? new String[0] : uri.getPath().split("/");
        return segments.length >= 5 && "p".equals(segments[1]) && "api".equals(segments[3])
            ? segments[2] : null;
    }

    private static String userId(WebSocketSession session) {
        Object userId = session.getAttributes().get(DashboardWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        Object role = session.getAttributes().get(DashboardWebSocketHandshakeInterceptor.USER_ROLE_ATTRIBUTE);
        return UserContext.ROLE_ADMIN.equals(role) ? null : userId instanceof String value ? value : null;
    }

    private static Duration clamp(Duration interval) {
        if (interval == null || interval.isNegative() || interval.isZero()) {
            return DEFAULT_POLL_INTERVAL;
        }
        return interval.compareTo(MAX_POLL_INTERVAL) > 0 ? MAX_POLL_INTERVAL : interval;
    }

    /** Test hook: block until all queued streaming tasks have completed. */
    void awaitStreaming() {
        try {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            executor.execute(done::countDown);
            done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
