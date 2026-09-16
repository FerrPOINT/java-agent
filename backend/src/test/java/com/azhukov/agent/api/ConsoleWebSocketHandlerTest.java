package com.azhukov.agent.api;

import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.terminal.ProcessTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * docs/34 gap 2 (bounded subset): live output streaming for console command
 * tasks over WebSocket, reusing the ProcessTool ledger and the same
 * redaction as the REST surface. Interactive PTY remains a documented gap.
 */
class ConsoleWebSocketHandlerTest {

    private ProcessTool processTool;
    private Redactor redactor;
    private ObjectMapper objectMapper;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        processTool = mock(ProcessTool.class);
        redactor = mock(Redactor.class);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        objectMapper = new ObjectMapper();
        executor = Executors.newSingleThreadExecutor();
    }

    private ConsoleWebSocketHandler handler() {
        return new ConsoleWebSocketHandler(processTool, redactor, objectMapper,
            java.time.Duration.ofMillis(20), executor);
    }

    private static WebSocketSession session(String query) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(new URI("ws://localhost/api/console/ws" + query));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    void streamsRedactedOutputThenExitFrame() throws Exception {
        ProcessTool.ManagedProcess managed = mock(ProcessTool.ManagedProcess.class);
        when(processTool.findConsoleProcess("proc-1")).thenReturn(managed);
        AtomicInteger pollCount = new AtomicInteger();
        // First poll: alive with one line; second poll: exited with two lines.
        when(processTool.isProcessAlive(managed)).thenAnswer(inv -> pollCount.incrementAndGet() == 1);
        when(processTool.recentOutput(any(), anyInt()))
            .thenReturn(List.of("line-1"))
            .thenReturn(List.of("line-1", "line-2"));
        when(processTool.processId(managed)).thenReturn("proc-1");

        WebSocketSession session = session("?id=proc-1");
        ConsoleWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.awaitStreaming();

        verify(session, atLeastOnce()).sendMessage(any(TextMessage.class));
        List<String> frames = capturedFrames(session);
        // Delta streaming: line-1 first, then the line-2 delta; no line is
        // ever re-sent across frames.
        List<String> streamed = new java.util.ArrayList<>();
        for (String frame : frames) {
            Map<String, Object> payload = objectMapper.readValue(frame, Map.class);
            if ("output".equals(payload.get("type"))) {
                streamed.addAll((List<String>) payload.get("lines"));
            }
        }
        assertThat(streamed).containsExactly("line-1", "line-2");
        assertThat(frames).anySatisfy(frame -> {
            Map<String, Object> payload = objectMapper.readValue(frame, Map.class);
            assertThat(payload.get("type")).isEqualTo("exit");
            assertThat(payload.get("id")).isEqualTo("proc-1");
        });
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    void rejectsConnectionWithoutIdParameter() throws Exception {
        WebSocketSession session = session("");
        handler().afterConnectionEstablished(session);
        verify(session).close(CloseStatus.BAD_DATA);
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void closesWithNotFoundForUnknownTask() throws Exception {
        when(processTool.findConsoleProcess("nope")).thenReturn(null);
        WebSocketSession session = session("?id=nope");
        handler().afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void redactsEveryStreamedLine() throws Exception {
        ProcessTool.ManagedProcess managed = mock(ProcessTool.ManagedProcess.class);
        when(processTool.findConsoleProcess("proc-2")).thenReturn(managed);
        when(processTool.processId(managed)).thenReturn("proc-2");
        when(processTool.isProcessAlive(managed)).thenReturn(false);
        when(processTool.recentOutput(any(), anyInt()))
            .thenReturn(List.of("secret-line"));
        when(redactor.redact("secret-line")).thenReturn("[REDACTED]");

        WebSocketSession session = session("?id=proc-2");
        ConsoleWebSocketHandler handler = handler();
        handler.afterConnectionEstablished(session);
        handler.awaitStreaming();

        verify(redactor).redact("secret-line");
        List<String> frames = capturedFrames(session);
        assertThat(frames).anySatisfy(frame -> {
            Map<String, Object> payload = objectMapper.readValue(frame, Map.class);
            if ("output".equals(payload.get("type"))) {
                assertThat((List<String>) payload.get("lines")).containsExactly("[REDACTED]");
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedFrames(WebSocketSession session) throws IOException {
        org.mockito.ArgumentCaptor<TextMessage> captor =
            org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(TextMessage::getPayload).toList();
    }
}
