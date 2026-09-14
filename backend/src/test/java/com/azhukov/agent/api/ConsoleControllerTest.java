package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.terminal.ProcessTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Bounded REST console (docs/34 gap 1 REST subset): guard enforcement,
 * lifecycle mapping and fail-closed unsupported markers.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleControllerTest {

    @Mock
    private ProcessTool processTool;
    @Mock
    private Redactor redactor;
    @Mock
    private ProcessTool.ManagedProcess managedProcess;

    private AgentProperties properties;
    private ConsoleController controller;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        controller = new ConsoleController(processTool, properties, redactor);
    }

    @Test
    void startRequiresCommand() {
        ResponseEntity<Map<String, Object>> response =
            controller.start(null, Map.of());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void startRejectsBlockedCommandWithForbidden() {
        ResponseEntity<Map<String, Object>> response =
            controller.start(null, Map.of("command", "sudo rm -rf /"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "command blocked by console guard");
    }

    @Test
    void startSpawnsGuardedTaskAndReturnsAccepted() throws IOException {
        when(processTool.spawn(anyString(), anyInt(), anyBoolean(), any(), any()))
            .thenReturn(managedProcess);
        when(processTool.processId(managedProcess)).thenReturn("proc_abc123");
        when(processTool.isProcessAlive(managedProcess)).thenReturn(true);
        when(redactor.redact("echo hi")).thenReturn("echo hi");

        ResponseEntity<Map<String, Object>> response =
            controller.start(null, Map.of("command", "echo hi", "timeout_seconds", 30));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody())
            .containsEntry("id", "proc_abc123")
            .containsEntry("status", "running");
    }

    @Test
    void statusUnknownTaskIsNotFound() {
        when(processTool.findConsoleProcess("nope")).thenReturn(null);
        ResponseEntity<Map<String, Object>> response = controller.status("nope");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statusReportsRunningAndExitedStates() {
        when(processTool.findConsoleProcess("proc_abc123")).thenReturn(managedProcess);
        when(processTool.isProcessAlive(managedProcess)).thenReturn(true);
        assertThat(controller.status("proc_abc123").getBody())
            .containsEntry("status", "running");

        when(processTool.isProcessAlive(managedProcess)).thenReturn(false);
        assertThat(controller.status("proc_abc123").getBody())
            .containsEntry("status", "exited");
    }

    @Test
    void outputReturnsRedactedRecentLines() {
        when(processTool.findConsoleProcess("proc_abc123")).thenReturn(managedProcess);
        when(processTool.recentOutput(managedProcess, 200))
            .thenReturn(List.of("plain", "SECRET_TOKEN"));
        when(redactor.redact("plain")).thenReturn("plain");
        when(redactor.redact("SECRET_TOKEN")).thenReturn("[REDACTED]");

        ResponseEntity<Map<String, Object>> response =
            controller.output("proc_abc123", 0, 200);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) response.getBody().get("lines");
        assertThat(lines).containsExactly("plain", "[REDACTED]");
    }

    @Test
    void killTerminatesWithConsoleSource() {
        when(processTool.findConsoleProcess("proc_abc123")).thenReturn(managedProcess);
        ResponseEntity<Map<String, Object>> response = controller.kill("proc_abc123");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(processTool).killProcess(managedProcess, "console.kill");
    }

    @Test
    void ptySurfaceFailsClosed() {
        assertThat(controller.unsupported().getStatusCode())
            .isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }
}
