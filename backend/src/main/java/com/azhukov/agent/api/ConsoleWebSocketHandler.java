package com.azhukov.agent.api;

import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.terminal.ProcessTool;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live output streaming for console command tasks (docs/34 gap 2, bounded
 * subset). Reuses the {@link ProcessTool} ledger: the client opens
 * {@code /api/console/ws?id=<task>} and receives JSON frames
 * {@code {"type":"output","id":...,"lines":[...]}} while the task runs and a
 * final {@code {"type":"exit",...}} frame before a normal close. Every line
 * is redacted with the same {@link Redactor} as the REST surface. Interactive
 * PTY ({@code /api/pty}) remains a documented gap and fails closed.
 */
@Component
@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_LINES_PER_FRAME = 500;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(2);

    private final ProcessTool processTool;
    private final Redactor redactor;
    private final ObjectMapper objectMapper;
    private final Duration pollInterval;
    private final ExecutorService executor;

    @Autowired
    public ConsoleWebSocketHandler(ProcessTool processTool,
                                   Redactor redactor,
                                   ObjectMapper objectMapper) {
        this(processTool, redactor, objectMapper, DEFAULT_POLL_INTERVAL,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("console-ws-", 0).factory()));
    }

    ConsoleWebSocketHandler(ProcessTool processTool,
                            Redactor redactor,
                            ObjectMapper objectMapper,
                            Duration pollInterval,
                            ExecutorService executor) {
        this.processTool = processTool;
        this.redactor = redactor;
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
        ProcessTool.ManagedProcess managed = processTool.findConsoleProcess(taskId);
        if (managed == null) {
            send(session, errorFrame("unknown_command_task", taskId));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        executor.execute(() -> stream(session, taskId, managed));
    }

    private void stream(WebSocketSession session, String taskId, ProcessTool.ManagedProcess managed) {
        int sentLines = 0;
        try {
            while (session.isOpen()) {
                List<String> lines = processTool.recentOutput(managed, MAX_LINES_PER_FRAME);
                if (lines.size() > sentLines) {
                    List<String> delta = lines.subList(sentLines, lines.size()).stream()
                        .map(redactor::redact)
                        .toList();
                    sentLines = lines.size();
                    send(session, frame("output", taskId, delta));
                }
                if (!processTool.isProcessAlive(managed)) {
                    // Final flush: emit everything produced between the last
                    // frame and process exit, then the exit frame.
                    List<String> tail = processTool.recentOutput(managed, MAX_LINES_PER_FRAME);
                    if (tail.size() > sentLines) {
                        List<String> delta = tail.subList(sentLines, tail.size()).stream()
                            .map(redactor::redact)
                            .toList();
                        sentLines = tail.size();
                        send(session, frame("output", taskId, delta));
                    }
                    send(session, frame("exit", taskId, null));
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

    private Map<String, Object> frame(String type, String taskId, List<String> lines) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("id", taskId);
        if (lines != null) {
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
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "id".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
