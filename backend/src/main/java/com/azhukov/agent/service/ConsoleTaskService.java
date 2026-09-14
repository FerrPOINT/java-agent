package com.azhukov.agent.service;

import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.persistence.entity.ConsoleTaskEntity;
import com.azhukov.agent.persistence.entity.ConsoleTaskOutputEntity;
import com.azhukov.agent.persistence.repository.ConsoleTaskOutputRepository;
import com.azhukov.agent.persistence.repository.ConsoleTaskRepository;
import com.azhukov.agent.tools.terminal.CommandGuard;
import com.azhukov.agent.tools.terminal.ProcessTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP-9 (ADR-015): durable console command tasks.
 *
 * <p>Commands pass {@link CommandGuard} BEFORE a task row is created, then
 * run through the existing {@link ProcessTool}. Redacted output lines persist
 * with a monotonic per-task sequence — reconnect replays strictly after the
 * client cursor (no duplicates, no gaps). Terminal transitions are guarded
 * and idempotent; retention sweeps expired tasks (24h TTL).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsoleTaskService {

    public static final int MAX_LINES_PER_TASK = 5_000;
    private static final int MAX_REPLAY_LIMIT = 2_000;

    private final ObjectProvider<ProcessTool> processToolProvider;
    private final ObjectProvider<ConsoleTaskRepository> taskRepositoryProvider;
    private final ObjectProvider<ConsoleTaskOutputRepository> outputRepositoryProvider;
    private final ObjectProvider<CommandGuard> commandGuardProvider;
    private final ObjectProvider<Redactor> redactorProvider;

    /** Live task runners (process + tailer); NOT restart-safe by design — rows survive. */
    private final ConcurrentHashMap<String, ProcessTool.ManagedProcess> live = new ConcurrentHashMap<>();

    public record StartResult(String id, String error, Integer httpStatus) {}

    public StartResult start(String profile, String userId, String command, String workdir,
                             int timeoutSeconds) {
        CommandGuard guard = commandGuardProvider == null ? null : commandGuardProvider.getIfAvailable();
        if (guard != null) {
            String blocked = guard.check(command);
            if (blocked != null) {
                return new StartResult(null, "command blocked: " + blocked, 403);
            }
        }
        ProcessTool processTool = processTool();
        ConsoleTaskEntity task = new ConsoleTaskEntity();
        task.setId("task_" + UUID.randomUUID().toString().replace("-", ""));
        task.setProfile(profile == null || profile.isBlank() ? "default" : profile);
        task.setUserId(userId);
        task.setCommand(command);
        task.setWorkdir(workdir);
        task.setTimeoutSeconds(timeoutSeconds);
        task.setState("running");
        tasks().save(task);

        try {
            ProcessTool.ManagedProcess managed =
                processTool.spawn(command, timeoutSeconds, false, null, workdir);
            live.put(task.getId(), managed);
            tailOutput(task.getId(), managed);
            return new StartResult(task.getId(), null, null);
        } catch (IOException e) {
            finish(task.getId(), "failed", -1);
            return new StartResult(task.getId(), "failed to start: " + e.getMessage(), 500);
        }
    }

    public Optional<ConsoleTaskEntity> status(String profile, String id) {
        sweepExpired();
        return tasks().findByIdAndProfile(id, profile == null || profile.isBlank()
            ? "default" : profile);
    }

    /** Cursor replay: lines with sequence strictly AFTER the cursor. */
    public Map<String, Object> output(String taskId, long after, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_REPLAY_LIMIT);
        List<ConsoleTaskOutputEntity> rows =
            outputs().replayAfter(taskId, after, PageRequest.of(0, bounded));
        List<Map<String, Object>> lines = new ArrayList<>(rows.size());
        long lastSeq = after;
        for (ConsoleTaskOutputEntity row : rows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("seq", row.getSequence());
            line.put("text", row.getLine());
            lines.add(line);
            lastSeq = row.getSequence();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", taskId);
        payload.put("cursor", lastSeq);
        payload.put("lines", lines);
        return payload;
    }

    /** Idempotent cancellation: kill the process and mark the row once. */
    public boolean cancel(String profile, String id) {
        Optional<ConsoleTaskEntity> existing = status(profile, id);
        if (existing.isEmpty()) {
            return false;
        }
        ConsoleTaskEntity task = existing.get();
        if ("running".equals(task.getState())) {
            ProcessTool.ManagedProcess managed = live.remove(id);
            if (managed != null) {
                processTool().killProcess(managed, "console.cancel");
            }
            finish(id, "cancelled", null);
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String taskId, String state, Integer exitCode) {
        tasks().finishTask(taskId, state, exitCode, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long appendLine(String taskId, String rawLine) {
        ConsoleTaskOutputRepository outputs = outputs();
        ConsoleTaskEntity task = tasks().findById(taskId).orElse(null);
        if (task == null) {
            return -1;
        }
        long seq = outputs.findFirstByTaskIdOrderBySequenceDesc(taskId)
            .map(ConsoleTaskOutputEntity::getSequence).orElse(0L) + 1;
        if (seq > MAX_LINES_PER_TASK) {
            return -1; // retention cap — drop further output
        }
        ConsoleTaskOutputEntity line = new ConsoleTaskOutputEntity();
        line.setTaskId(taskId);
        line.setSequence(seq);
        line.setLine(redact(rawLine));
        outputs.save(line);
        return seq;
    }

    public int sweepExpired() {
        try {
            return tasks().deleteExpired(Instant.now());
        } catch (Exception e) {
            log.debug("console task sweep failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private void tailOutput(String taskId, ProcessTool.ManagedProcess managed) {
        Thread.ofVirtual().name("console-task-" + taskId).start(() -> {
            ProcessTool processTool = processTool();
            try {
                while (processTool.isProcessAlive(managed)) {
                    for (String line : processTool.recentOutput(managed, 200)) {
                        appendLine(taskId, line);
                    }
                    Thread.sleep(250);
                }
                // final drain
                for (String line : processTool.recentOutput(managed, 2000)) {
                    appendLine(taskId, line);
                }
                finish(taskId, "completed", 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish(taskId, "cancelled", null);
            } catch (Exception e) {
                log.warn("Console task tailer failed for {}: {}", taskId, e.getMessage());
                finish(taskId, "failed", -1);
            } finally {
                live.remove(taskId);
            }
        });
    }

    private String redact(String line) {
        Redactor redactor = redactorProvider == null ? null : redactorProvider.getIfAvailable();
        return redactor == null ? line : redactor.redact(line);
    }

    private ProcessTool processTool() {
        ProcessTool processTool = processToolProvider == null
            ? null : processToolProvider.getIfAvailable();
        if (processTool == null) {
            throw new IllegalStateException("process tool is unavailable");
        }
        return processTool;
    }

    private ConsoleTaskRepository tasks() {
        ConsoleTaskRepository repository = taskRepositoryProvider == null
            ? null : taskRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("console task repository is unavailable");
        }
        return repository;
    }

    private ConsoleTaskOutputRepository outputs() {
        ConsoleTaskOutputRepository repository = outputRepositoryProvider == null
            ? null : outputRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("console output repository is unavailable");
        }
        return repository;
    }
}
