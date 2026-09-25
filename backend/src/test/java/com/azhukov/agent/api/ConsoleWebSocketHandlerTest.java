package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.ConsoleTaskEntity;
import com.azhukov.agent.service.ConsoleTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Durable console WebSocket replay must retain task ownership and cursor semantics. */
class ConsoleWebSocketHandlerTest {

    private ConsoleTaskService taskService;
    private ObjectMapper objectMapper;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        taskService = mock(ConsoleTaskService.class);
        objectMapper = new ObjectMapper();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private ConsoleWebSocketHandler handler() {
        return new ConsoleWebSocketHandler(taskService, objectMapper,
            java.time.Duration.ofMillis(20), executor);
    }

    private static WebSocketSession session(String query) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("ws://localhost/api/console/ws" + query));
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new java.util.concurrent.ConcurrentHashMap<>());
        return session;
    }

    private static ConsoleTaskEntity task(String state) {
        ConsoleTaskEntity task = new ConsoleTaskEntity();
        task.setState(state);
        return task;
    }

    @Test
    void streamsDurableCursorReplayThenExitFrame() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        when(taskService.status(null, null, "task-1"))
            .thenAnswer(inv -> Optional.of(task(statusCalls.incrementAndGet() == 1 ? "running" : "completed")));
        when(taskService.output(null, null, "task-1", 0, 500)).thenReturn(Optional.of(Map.of(
            "id", "task-1", "cursor", 2L,
            "lines", List.of(Map.of("seq", 1L, "text", "line-1"), Map.of("seq", 2L, "text", "line-2")))));

        WebSocketSession session = session("?id=task-1");
        ConsoleWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.awaitStreaming();

        List<String> frames = capturedFrames(session);
        Map<String, Object> output = objectMapper.readValue(frames.getFirst(), Map.class);
        assertThat(output).containsEntry("type", "output").containsEntry("cursor", 2);
        assertThat((List<?>) output.get("lines")).hasSize(2);
        assertThat(frames).anySatisfy(frame -> {
            Map<String, Object> payload = objectMapper.readValue(frame, Map.class);
            assertThat(payload).containsEntry("type", "exit").containsEntry("cursor", 2);
        });
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    void replaysOnlyOutputStrictlyAfterReconnectCursor() throws Exception {
        when(taskService.status(null, null, "task-1")).thenReturn(Optional.of(task("completed")));
        when(taskService.output(null, null, "task-1", 7, 500)).thenReturn(Optional.of(Map.of(
            "id", "task-1", "cursor", 8L,
            "lines", List.of(Map.of("seq", 8L, "text", "new")))));

        WebSocketSession session = session("?id=task-1&after=7");
        ConsoleWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.awaitStreaming();

        verify(taskService).output(null, null, "task-1", 7, 500);
        List<String> frames = capturedFrames(session);
        Map<String, Object> output = objectMapper.readValue(frames.getFirst(), Map.class);
        assertThat(output).containsEntry("cursor", 8);
        assertThat((List<Map<String, Object>>) output.get("lines"))
            .extracting(line -> line.get("seq")).containsExactly(8);
    }

    @Test
    void rejectsConnectionWithoutIdParameter() throws Exception {
        WebSocketSession session = session("");
        handler().afterConnectionEstablished(session);
        verify(session).close(CloseStatus.BAD_DATA);
        verify(session, never()).sendMessage(org.mockito.ArgumentMatchers.any(TextMessage.class));
    }

    @Test
    void rejectsUnknownOrNonOwnedTaskBeforeStreaming() throws Exception {
        when(taskService.status(null, "user-a", "task-1")).thenReturn(Optional.empty());
        WebSocketSession session = session("?id=task-1");
        session.getAttributes().put(DashboardWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE, "user-a");
        handler().afterConnectionEstablished(session);

        verify(taskService).status(null, "user-a", "task-1");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedFrames(WebSocketSession session) throws IOException {
        org.mockito.ArgumentCaptor<TextMessage> captor =
            org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }
}
