package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.tools.terminal.CommandGuard;
import com.azhukov.agent.tools.terminal.ProcessTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded REST console surface (docs/34 gap 1, REST subset).
 *
 * Hermes ties the full {@code /api/console} to HermesConsoleEngine with
 * confirmation state, cancelable command tasks and WS auth/close semantics;
 * those stay a documented gap. What IS bounded here: dashboard-driven
 * command tasks over the existing {@link ProcessTool} ledger —
 * start/status/output/kill/list — behind the same {@link CommandGuard}
 * policy the terminal tool uses, with output redaction.
 *
 * Live output streaming is provided by {@link ConsoleWebSocketHandler} on
 * {@code /api/console/ws?id=<task>} (redacted frames + exit frame). The
 * reconnect/cursor protocol and interactive {@code /api/pty} remain
 * explicit unsupported gaps and fail closed.
 */
@RestController
@RequestMapping({"/api/console", "/p/{profile}/api/console"})
public class ConsoleController {

    private static final int DEFAULT_OUTPUT_LIMIT = 200;
    private static final int MAX_OUTPUT_LIMIT = 2000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    private final ProcessTool processTool;
    private final CommandGuard commandGuard;
    private final Redactor redactor;

    public ConsoleController(ProcessTool processTool,
                             AgentProperties properties,
                             Redactor redactor) {
        this.processTool = processTool;
        this.commandGuard = new CommandGuard(
            properties.getSecurity().getBlockedCommands(),
            properties.getTerminal().isBlockSudo());
        this.redactor = redactor;
    }

    /** Start a guarded background command task. */
    @PostMapping("/commands")
    public ResponseEntity<Map<String, Object>> start(@PathVariable(name = "profile", required = false) String pathProfile,
                                                     @RequestBody Map<String, Object> body) {
        String command = body.get("command") instanceof String s ? s.trim() : "";
        if (command.isEmpty()) {
            return badRequest("command is required");
        }
        int timeout = intField(body.get("timeout_seconds"), DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, "timeout_seconds");
        String workdir = body.get("workdir") instanceof String s && !s.isBlank() ? s : null;

        String blocked = commandGuard.check(command);
        if (blocked != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "command blocked by console guard", "detail", blocked));
        }

        try {
            ProcessTool.ManagedProcess managed =
                processTool.spawn(command, timeout, false, null, workdir);
            String processId = processTool.processId(managed);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", processId);
            response.put("command", redactor.redact(command));
            response.put("timeout_seconds", timeout);
            response.put("status", processTool.isProcessAlive(managed) ? "running" : "exited");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "failed to start command", "detail", String.valueOf(e.getMessage())));
        }
    }

    /** Status of one console command task. */
    @GetMapping("/commands/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        ProcessTool.ManagedProcess managed = processTool.findConsoleProcess(id);
        if (managed == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown command task"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("alive", processTool.isProcessAlive(managed));
        response.put("status", processTool.isProcessAlive(managed) ? "running" : "exited");
        return ResponseEntity.ok(response);
    }

    /** Redacted output snapshot of one console command task. */
    @GetMapping("/commands/{id}/output")
    public ResponseEntity<Map<String, Object>> output(@PathVariable String id,
                                                      @RequestParam(name = "offset", defaultValue = "0") int offset,
                                                      @RequestParam(name = "limit", defaultValue = "200") int limit) {
        ProcessTool.ManagedProcess managed = processTool.findConsoleProcess(id);
        if (managed == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown command task"));
        }
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_OUTPUT_LIMIT);
        List<String> lines = processTool.recentOutput(managed, boundedLimit);
        List<String> redacted = new ArrayList<>(lines.size());
        for (String line : lines) {
            redacted.add(redactor.redact(line));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("limit", boundedLimit);
        response.put("lines", redacted);
        return ResponseEntity.ok(response);
    }

    /** Kill a console command task. */
    @PostMapping("/commands/{id}/kill")
    public ResponseEntity<Map<String, Object>> kill(@PathVariable String id) {
        ProcessTool.ManagedProcess managed = processTool.findConsoleProcess(id);
        if (managed == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown command task"));
        }
        processTool.killProcess(managed, "console.kill");
        return ResponseEntity.ok(Map.of("id", id, "status", "killed"));
    }

    /**
     * Interactive PTY remains a documented gap and fails closed. Output
     * streaming lives on the WebSocket endpoint {@code /api/console/ws?id=...}
     * ({@link ConsoleWebSocketHandler}).
     */
    @GetMapping("/pty")
    public ResponseEntity<Map<String, Object>> unsupported() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "error", "console interactive PTY is not implemented",
            "detail", "Output streaming: WebSocket /api/console/ws?id=<task>. "
                + "REST command tasks: POST /api/console/commands, GET /commands/{id}, "
                + "GET /commands/{id}/output, POST /commands/{id}/kill"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static int intField(Object raw, int defaultValue, int maxValue, String field) {
        if (raw instanceof Number n) {
            int value = n.intValue();
            if (value <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return Math.min(value, maxValue);
        }
        return defaultValue;
    }
}
