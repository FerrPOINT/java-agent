package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.ConsoleTaskService;
import com.azhukov.agent.tools.terminal.ProcessTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controller contract for durable, owner-scoped console command tasks. */
@ExtendWith(MockitoExtension.class)
class ConsoleControllerTest {

    @Mock
    private ProcessTool processTool;
    @Mock
    private Redactor redactor;
    @Mock
    private ConsoleTaskService consoleTaskService;
    @Mock
    private com.azhukov.agent.service.PtySessionService ptySessionService;

    private ConsoleController controller;

    @BeforeEach
    void setUp() {
        controller = new ConsoleController(processTool, new AgentProperties(), redactor,
            provider(consoleTaskService), provider(ptySessionService), null);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void startRequiresCommand() {
        assertThat(controller.start(null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void startDelegatesToDurableTaskServiceWithAuthenticatedUser() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        when(consoleTaskService.start(null, "user-a", "echo hi", null, 30))
            .thenReturn(new ConsoleTaskService.StartResult("task_abc", null, null));
        when(redactor.redact("echo hi")).thenReturn("echo hi");

        ResponseEntity<Map<String, Object>> response =
            controller.start(null, Map.of("command", "echo hi", "timeout_seconds", 30));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", "task_abc");
        verify(consoleTaskService).start(null, "user-a", "echo hi", null, 30);
    }

    @Test
    void outputUsesDurableCursorReplayAndOwnerScope() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        Map<String, Object> payload = Map.of("id", "task_abc", "cursor", 3L, "lines", java.util.List.of());
        when(consoleTaskService.output(null, "user-a", "task_abc", 2, 200))
            .thenReturn(Optional.of(payload));

        ResponseEntity<Map<String, Object>> response = controller.output(null, "task_abc", 2, 200);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(payload);
        verify(consoleTaskService).output(null, "user-a", "task_abc", 2, 200);
    }

    @Test
    void killUsesDurableTaskServiceAndOwnerScope() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        when(consoleTaskService.cancel(null, "user-a", "task_abc")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.kill(null, "task_abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "cancelled");
        verify(consoleTaskService).cancel(null, "user-a", "task_abc");
    }

    @Test
    void ptyInputUsesAuthenticatedUserOwnership() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        when(ptySessionService.write(null, "user-a", "pty_abc", "echo hi\n")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response =
            controller.ptyInput(null, "pty_abc", Map.of("input", "echo hi\n"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ptySessionService).write(null, "user-a", "pty_abc", "echo hi\n");
    }

    @Test
    void ptyReadUsesAuthenticatedUserOwnership() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        when(ptySessionService.read(null, "user-a", "pty_abc", 0, 20))
            .thenReturn(new com.azhukov.agent.service.PtySessionService.PtyReadResult(
                java.util.List.of(), 0, true));

        ResponseEntity<Map<String, Object>> response = controller.ptyRead(null, "pty_abc", 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ptySessionService).read(null, "user-a", "pty_abc", 0, 20);
    }

    @Test
    void ptyCloseUsesAuthenticatedUserOwnership() {
        UserContext.set("user-a", UserContext.ROLE_USER);
        when(ptySessionService.close(null, "user-a", "pty_abc")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.ptyClose(null, "pty_abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ptySessionService).close(null, "user-a", "pty_abc");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return Stream.of(value); }
            @Override public Stream<T> orderedStream() { return Stream.of(value); }
        };
    }
}
